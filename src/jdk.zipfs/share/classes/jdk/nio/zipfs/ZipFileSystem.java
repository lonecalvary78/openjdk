/*
 * Copyright (c) 2009, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nio.zipfs;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Runtime.Version;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

import static java.lang.Boolean.TRUE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static jdk.nio.zipfs.ZipConstants.*;
import static jdk.nio.zipfs.ZipUtils.*;

/**
 * A FileSystem built on a zip file
 *
 * @author Xueming Shen
 */
class ZipFileSystem extends FileSystem {
    // statics
    private static final boolean isWindows = System.getProperty("os.name")
                                             .startsWith("Windows");
    private static final byte[] ROOTPATH = new byte[] { '/' };

    // Global access mode for "mounted" file system ("readOnly" or "readWrite").
    private static final String PROPERTY_ACCESS_MODE = "accessMode";

    // Posix file permissions allow per-file access control in a posix-like fashion.
    // Using a "readOnly" access mode will change the posix permissions of any
    // underlying entries (they may still show as "writable", but will not be).
    private static final String PROPERTY_POSIX = "enablePosixFileAttributes";
    private static final String PROPERTY_DEFAULT_OWNER = "defaultOwner";
    private static final String PROPERTY_DEFAULT_GROUP = "defaultGroup";
    private static final String PROPERTY_DEFAULT_PERMISSIONS = "defaultPermissions";
    // Property used to specify the entry version to use for a multi-release JAR
    private static final String PROPERTY_RELEASE_VERSION = "releaseVersion";

    // Original property used to specify the entry version to use for a
    // multi-release JAR which is kept for backwards compatibility.
    private static final String PROPERTY_MULTI_RELEASE = "multi-release";

    // Property used to specify the compression mode to use
    private static final String PROPERTY_COMPRESSION_METHOD = "compressionMethod";
    // Value specified for compressionMethod property to compress Zip entries
    private static final String COMPRESSION_METHOD_DEFLATED = "DEFLATED";
    // Value specified for compressionMethod property to not compress Zip entries
    private static final String COMPRESSION_METHOD_STORED = "STORED";

    private final ZipFileSystemProvider provider;
    private final Path zfpath;
    final ZipCoder zc;
    private final ZipPath rootdir;
    // Starts in readOnly (safe mode), but might be reset at the end of initialization.
    private boolean readOnly = true;

    // default time stamp for pseudo entries
    private final long zfsDefaultTimeStamp = System.currentTimeMillis();

    // configurable by env map
    private final boolean noExtt;        // see readExtra()
    private final boolean useTempFile;   // use a temp file for newOS, default
                                         // is to use BAOS for better performance

    // a threshold, in bytes, to decide whether to create a temp file
    // for outputstream of a zip entry
    private final int tempFileCreationThreshold = 10 * 1024 * 1024; // 10 MB

    private final boolean forceEnd64;
    private final int defaultCompressionMethod; // METHOD_STORED if "noCompression=true"
                                                // METHOD_DEFLATED otherwise

    // entryLookup is identity by default, will be overridden for multi-release jars
    private Function<byte[], byte[]> entryLookup = Function.identity();

    // POSIX support
    final boolean supportPosix;
    private final UserPrincipal defaultOwner;
    private final GroupPrincipal defaultGroup;
    // Unmodifiable set.
    private final Set<PosixFilePermission> defaultPermissions;

    private final Set<String> supportedFileAttributeViews;

    private enum ZipAccessMode {
        // Creates a file system for read-write access.
        READ_WRITE("readWrite"),
        // Creates a file system for read-only access.
        READ_ONLY("readOnly");

        private final String label;

        ZipAccessMode(String label) {
            this.label = label;
        }

        // Parses the access mode from an environmental parameter.
        // Returns null for missing value to indicate default behavior.
        static ZipAccessMode from(Object value) {
            if (value == null) {
                return null;
            } else if (READ_WRITE.label.equals(value)) {
                return ZipAccessMode.READ_WRITE;
            } else if (READ_ONLY.label.equals(value)) {
                return ZipAccessMode.READ_ONLY;
            }
            throw new IllegalArgumentException("Unknown file system access mode: " + value);
        }
    }

    ZipFileSystem(ZipFileSystemProvider provider,
                  Path zfpath,
                  Map<String, ?> env) throws IOException
    {
        // default encoding for name/comment
        String nameEncoding = env.containsKey("encoding") ?
            (String)env.get("encoding") : "UTF-8";
        this.noExtt = "false".equals(env.get("zipinfo-time"));
        this.useTempFile  = isTrue(env, "useTempFile");
        this.forceEnd64 = isTrue(env, "forceZIP64End");
        this.defaultCompressionMethod = getDefaultCompressionMethod(env);

        ZipAccessMode accessMode = ZipAccessMode.from(env.get(PROPERTY_ACCESS_MODE));
        boolean forceReadOnly = (accessMode == ZipAccessMode.READ_ONLY);

        this.supportPosix = isTrue(env, PROPERTY_POSIX);
        this.defaultOwner = supportPosix ? initOwner(zfpath, env) : null;
        this.defaultGroup = supportPosix ? initGroup(zfpath, env) : null;
        this.defaultPermissions = supportPosix ? Collections.unmodifiableSet(initPermissions(env)) : null;
        this.supportedFileAttributeViews = supportPosix ?
                Set.of("basic", "posix", "zip") : Set.of("basic", "zip");

        // 'create=true' is semantically the same as StandardOpenOption.CREATE,
        // and can only be used to create a writable file system (whether the
        // underlying ZIP file exists or not), and is always incompatible with
        // 'accessMode=readOnly').
        boolean shouldCreate = isTrue(env, "create");
        if (shouldCreate && forceReadOnly) {
            throw new IllegalArgumentException(
                    "Specifying 'accessMode=readOnly' is incompatible with 'create=true'");
        }
        if (Files.notExists(zfpath)) {
            if (shouldCreate) {
                try (OutputStream os = Files.newOutputStream(zfpath, CREATE_NEW, WRITE)) {
                    new END().write(os, 0, forceEnd64);
                }
            } else {
                throw new NoSuchFileException(zfpath.toString());
            }
        }
        // Existence check
        zfpath.getFileSystem().provider().checkAccess(zfpath, AccessMode.READ);
        this.zc = ZipCoder.get(nameEncoding);
        this.ch = Files.newByteChannel(zfpath, READ);
        try {
            this.cen = initCEN();
        } catch (IOException x) {
            try {
                this.ch.close();
            } catch (IOException xx) {
                x.addSuppressed(xx);
            }
            throw x;
        }
        this.provider = provider;
        this.zfpath = zfpath;
        this.rootdir = new ZipPath(this, new byte[]{'/'});

        // Determining a release version uses 'this' instance to read paths etc.
        Optional<Integer> multiReleaseVersion = determineReleaseVersion(env);

        // Set the version-based lookup function for multi-release JARs.
        this.entryLookup =
                multiReleaseVersion.map(this::createVersionedLinks).orElse(Function.identity());

        // We only allow read-write zip/jar files if they are not multi-release
        // JARs and the underlying file is writable.
        this.readOnly = forceReadOnly || multiReleaseVersion.isPresent() || !Files.isWritable(zfpath);
        if (readOnly && accessMode == ZipAccessMode.READ_WRITE) {
            String reason = multiReleaseVersion.isPresent()
                    ? "the multi-release JAR file is not writable"
                    : "the ZIP file is not writable";
            throw new IOException(reason);
        }
    }

    /**
     * Return the compression method to use (STORED or DEFLATED).  If the
     * property {@code compressionMethod} is set use its value to determine
     * the compression method to use.  If the property is not set, then the
     * default compression is DEFLATED unless the property {@code noCompression}
     * is set which is supported for backwards compatibility.
     * @param env Zip FS map of properties
     * @return The Compression method to use
     */
    private int getDefaultCompressionMethod(Map<String, ?> env) {
        int result =
                isTrue(env, "noCompression") ? METHOD_STORED : METHOD_DEFLATED;
        if (env.containsKey(PROPERTY_COMPRESSION_METHOD)) {
            Object compressionMethod =  env.get(PROPERTY_COMPRESSION_METHOD);
            if (compressionMethod != null) {
                if (compressionMethod instanceof String) {
                    switch (((String) compressionMethod).toUpperCase()) {
                        case COMPRESSION_METHOD_STORED:
                            result = METHOD_STORED;
                            break;
                        case COMPRESSION_METHOD_DEFLATED:
                            result = METHOD_DEFLATED;
                            break;
                        default:
                            throw new IllegalArgumentException(String.format(
                                    "The value for the %s property must be %s or %s",
                                    PROPERTY_COMPRESSION_METHOD, COMPRESSION_METHOD_STORED,
                                    COMPRESSION_METHOD_DEFLATED));
                    }
                } else {
                    throw new IllegalArgumentException(String.format(
                            "The Object type for the %s property must be a String",
                            PROPERTY_COMPRESSION_METHOD));
                }
            } else {
                throw new IllegalArgumentException(String.format(
                        "The value for the %s property must be %s or %s",
                        PROPERTY_COMPRESSION_METHOD, COMPRESSION_METHOD_STORED,
                        COMPRESSION_METHOD_DEFLATED));
            }
        }
        return result;
    }

    // returns true if there is a name=true/"true" setting in env
    private static boolean isTrue(Map<String, ?> env, String name) {
        return "true".equals(env.get(name)) || TRUE.equals(env.get(name));
    }

    // Initialize the default owner for files inside the zip archive.
    // If not specified in env, it is the owner of the archive. If no owner can
    // be determined, we try to go with system property "user.name". If that's not
    // accessible, we return "<zipfs_default>".
    private UserPrincipal initOwner(Path zfpath, Map<String, ?> env) throws IOException {
        Object o = env.get(PROPERTY_DEFAULT_OWNER);
        if (o == null) {
            try {
                return Files.getOwner(zfpath);
            } catch (UnsupportedOperationException | NoSuchFileException e) {
                String userName = System.getProperty("user.name");
                return ()->userName;
            }
        }
        if (o instanceof String) {
            if (((String)o).isEmpty()) {
                throw new IllegalArgumentException("Value for property " +
                        PROPERTY_DEFAULT_OWNER + " must not be empty.");
            }
            return ()->(String)o;
        }
        if (o instanceof UserPrincipal) {
            return (UserPrincipal)o;
        }
        throw new IllegalArgumentException("Value for property " +
                PROPERTY_DEFAULT_OWNER + " must be of type " + String.class +
            " or " + UserPrincipal.class);
    }

    // Initialize the default group for files inside the zip archive.
    // If not specified in env, we try to determine the group of the zip archive itself.
    // If this is not possible/unsupported, we will return a group principal going by
    // the same name as the default owner.
    private GroupPrincipal initGroup(Path zfpath, Map<String, ?> env) throws IOException {
        Object o = env.get(PROPERTY_DEFAULT_GROUP);
        if (o == null) {
            try {
                PosixFileAttributeView zfpv = Files.getFileAttributeView(zfpath, PosixFileAttributeView.class);
                if (zfpv == null) {
                    return defaultOwner::getName;
                }
                return zfpv.readAttributes().group();
            } catch (UnsupportedOperationException | NoSuchFileException e) {
                return defaultOwner::getName;
            }
        }
        if (o instanceof String) {
            if (((String)o).isEmpty()) {
                throw new IllegalArgumentException("Value for property " +
                        PROPERTY_DEFAULT_GROUP + " must not be empty.");
            }
            return ()->(String)o;
        }
        if (o instanceof GroupPrincipal) {
            return (GroupPrincipal)o;
        }
        throw new IllegalArgumentException("Value for property " +
                PROPERTY_DEFAULT_GROUP + " must be of type " + String.class +
            " or " + GroupPrincipal.class);
    }

    // Return the default permissions for files inside the zip archive.
    // If not specified in env, it will return 777.
    private Set<PosixFilePermission> initPermissions(Map<String, ?> env) {
        Object o = env.get(PROPERTY_DEFAULT_PERMISSIONS);
        if (o == null) {
            return PosixFilePermissions.fromString("rwxrwxrwx");
        }
        if (o instanceof String) {
            return PosixFilePermissions.fromString((String)o);
        }
        if (!(o instanceof Set)) {
            throw new IllegalArgumentException("Value for property " +
                PROPERTY_DEFAULT_PERMISSIONS + " must be of type " + String.class +
                " or " + Set.class);
        }
        Set<PosixFilePermission> perms = new HashSet<>();
        for (Object o2 : (Set<?>)o) {
            if (o2 instanceof PosixFilePermission) {
                perms.add((PosixFilePermission)o2);
            } else {
                throw new IllegalArgumentException(PROPERTY_DEFAULT_PERMISSIONS +
                    " must only contain objects of type " + PosixFilePermission.class);
            }
        }
        return perms;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    private void checkWritable() {
        if (readOnly) {
            throw new ReadOnlyFileSystemException();
        }
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(rootdir);
    }

    ZipPath getRootDir() {
        return rootdir;
    }

    @Override
    public ZipPath getPath(String first, String... more) {
        if (more.length == 0) {
            return new ZipPath(this, first);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(first);
        for (String path : more) {
            if (path.length() > 0) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(path);
            }
        }
        return new ZipPath(this, sb.toString());
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    FileStore getFileStore(ZipPath path) {
        return new ZipFileStore(path);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of(new ZipFileStore(rootdir));
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return supportedFileAttributeViews;
    }

    @Override
    public String toString() {
        return zfpath.toString();
    }

    Path getZipFile() {
        return zfpath;
    }

    private static final String GLOB_SYNTAX = "glob";
    private static final String REGEX_SYNTAX = "regex";

    @Override
    public PathMatcher getPathMatcher(String syntaxAndInput) {
        int pos = syntaxAndInput.indexOf(':');
        if (pos <= 0) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndInput.substring(0, pos);
        String input = syntaxAndInput.substring(pos + 1);
        String expr;
        if (syntax.equalsIgnoreCase(GLOB_SYNTAX)) {
            expr = toRegexPattern(input);
        } else {
            if (syntax.equalsIgnoreCase(REGEX_SYNTAX)) {
                expr = input;
            } else {
                throw new UnsupportedOperationException("Syntax '" + syntax +
                    "' not recognized");
            }
        }
        // return matcher
        final Pattern pattern = Pattern.compile(expr);
        return (path)->pattern.matcher(path.toString()).matches();
    }

    @Override
    public void close() throws IOException {
        beginWrite();
        try {
            if (!isOpen)
                return;
            isOpen = false;          // set closed
        } finally {
            endWrite();
        }
        if (!streams.isEmpty()) {    // unlock and close all remaining streams
            Set<InputStream> copy = new HashSet<>(streams);
            for (InputStream is : copy)
                is.close();
        }
        beginWrite();                // lock and sync
        try {
            sync();
            ch.close();              // close the ch just in case no update
                                     // and sync didn't close the ch
        } finally {
            endWrite();
        }

        synchronized (inflaters) {
            for (Inflater inf : inflaters)
                inf.end();
        }
        synchronized (deflaters) {
            for (Deflater def : deflaters)
                def.end();
        }

        beginWrite();                // lock and sync
        try {
            // Clear the map so that its keys & values can be garbage collected
            inodes = null;
        } finally {
            endWrite();
        }

        IOException ioe = null;
        synchronized (tmppaths) {
            for (Path p : tmppaths) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException x) {
                    if (ioe == null)
                        ioe = x;
                    else
                        ioe.addSuppressed(x);
                }
            }
        }
        provider.removeFileSystem(zfpath, this);
        if (ioe != null)
           throw ioe;
    }

    ZipFileAttributes getFileAttributes(byte[] path)
        throws IOException
    {
        beginRead();
        try {
            ensureOpen();
            IndexNode inode = getInode(path);
            if (inode == null) {
                return null;
            } else if (inode instanceof Entry) {
                return (Entry)inode;
            } else if (inode.pos == -1) {
                // pseudo directory, uses METHOD_STORED
                Entry e = supportPosix ?
                    new PosixEntry(inode.name, inode.isdir, METHOD_STORED) :
                    new Entry(inode.name, inode.isdir, METHOD_STORED);
                e.mtime = e.atime = e.ctime = zfsDefaultTimeStamp;
                return e;
            } else {
                return supportPosix ? new PosixEntry(this, inode) : new Entry(this, inode);
            }
        } finally {
            endRead();
        }
    }

    void checkAccess(byte[] path) throws IOException {
        beginRead();
        try {
            ensureOpen();
            // is it necessary to readCEN as a sanity check?
            if (getInode(path) == null) {
                throw new NoSuchFileException(toString());
            }

        } finally {
            endRead();
        }
    }

    void setTimes(byte[] path, FileTime mtime, FileTime atime, FileTime ctime)
        throws IOException
    {
        checkWritable();
        beginWrite();
        try {
            ensureOpen();
            Entry e = getEntry(path);    // ensureOpen checked
            if (e == null)
                throw new NoSuchFileException(getString(path));
            if (e.type == Entry.CEN)
                e.type = Entry.COPY;     // copy e
            if (mtime != null)
                e.mtime = mtime.toMillis();
            if (atime != null)
                e.atime = atime.toMillis();
            if (ctime != null)
                e.ctime = ctime.toMillis();
            update(e);
        } finally {
            endWrite();
        }
    }

    void setOwner(byte[] path, UserPrincipal owner) throws IOException {
        checkWritable();
        beginWrite();
        try {
            ensureOpen();
            Entry e = getEntry(path);    // ensureOpen checked
            if (e == null) {
                throw new NoSuchFileException(getString(path));
            }
            // as the owner information is not persistent, we don't need to
            // change e.type to Entry.COPY
            if (e instanceof PosixEntry) {
                ((PosixEntry)e).owner = owner;
                update(e);
            }
        } finally {
            endWrite();
        }
    }

    void setGroup(byte[] path, GroupPrincipal group) throws IOException {
        checkWritable();
        beginWrite();
        try {
            ensureOpen();
            Entry e = getEntry(path);    // ensureOpen checked
            if (e == null) {
                throw new NoSuchFileException(getString(path));
            }
            // as the group information is not persistent, we don't need to
            // change e.type to Entry.COPY
            if (e instanceof PosixEntry) {
                ((PosixEntry)e).group = group;
                update(e);
            }
        } finally {
            endWrite();
        }
    }

    void setPermissions(byte[] path, Set<PosixFilePermission> perms) throws IOException {
        checkWritable();
        beginWrite();
        try {
            ensureOpen();
            Entry e = getEntry(path);    // ensureOpen checked
            if (e == null) {
                throw new NoSuchFileException(getString(path));
            }
            if (e.type == Entry.CEN) {
                e.type = Entry.COPY;     // copy e
            }
            if (perms == null) {
                e.externalFileAttributes = -1;
            } else if (e.externalFileAttributes == -1) {
                e.externalFileAttributes = ZipUtils.permsToFlags(perms);
            } else {
                e.externalFileAttributes = ZipUtils.permsToFlags(perms) |
                        (e.externalFileAttributes & 0xFE00); // Preserve unrelated bits
            }
            update(e);
        } finally {
            endWrite();
        }
    }

    boolean exists(byte[] path) {
        beginRead();
        try {
            ensureOpen();
            return getInode(path) != null;
        } finally {
            endRead();
        }
    }

    boolean isDirectory(byte[] path) {
        beginRead();
        try {
            IndexNode n = getInode(path);
            return n != null && n.isDir();
        } finally {
            endRead();
        }
    }

    // returns the list of child paths of "path"
    Iterator<Path> iteratorOf(ZipPath dir,
                              DirectoryStream.Filter<? super Path> filter)
        throws IOException
    {
        beginWrite();    // iteration of inodes needs exclusive lock
        try {
            ensureOpen();
            byte[] path = dir.getResolvedPath();
            IndexNode inode = getInode(path);
            if (inode == null)
                throw new NotDirectoryException(getString(path));
            List<Path> list = new ArrayList<>();
            IndexNode child = inode.child;
            while (child != null) {
                // (1) Assume each path from the zip file itself is "normalized"
                // (2) IndexNode.name is absolute. see IndexNode(byte[],int,int)
                // (3) If parent "dir" is relative when ZipDirectoryStream
                //     is created, the returned child path needs to be relative
                //     as well.
                ZipPath childPath = new ZipPath(this, child.name, true);
                ZipPath childFileName = childPath.getFileName();
                ZipPath zpath = dir.resolve(childFileName);
                if (filter == null || filter.accept(zpath))
                    list.add(zpath);
                child = child.sibling;
            }
            return list.iterator();
        } finally {
            endWrite();
        }
    }

    void createDirectory(byte[] dir, FileAttribute<?>... attrs) throws IOException {
        checkWritable();
        beginWrite();
        try {
            ensureOpen();
            if (dir.length == 0 || exists(dir))  // root dir, or existing dir
                throw new FileAlreadyExistsException(getString(dir));
            checkParents(dir);
            Entry e = supportPosix ?
                new PosixEntry(dir, Entry.NEW, true, METHOD_STORED, attrs) :
                new Entry(dir, Entry.NEW, true, METHOD_STORED, attrs);
            update(e);
        } finally {
            endWrite();
        }
    }

    void copyFile(boolean deletesrc, byte[]src, byte[] dst, CopyOption... options)
        throws IOException
    {
        checkWritable();
        if (Arrays.equals(src, dst))
            return;    // do nothing, src and dst are the same

        beginWrite();
        try {
            ensureOpen();
            Entry eSrc = getEntry(src);  // ensureOpen checked

            if (eSrc == null)
                throw new NoSuchFileException(getString(src));
            if (eSrc.isDir()) {    // spec says to create dst dir
                createDirectory(dst);
                return;
            }
            boolean hasReplace = false;
            boolean hasCopyAttrs = false;
            for (CopyOption opt : options) {
                if (opt == REPLACE_EXISTING)
                    hasReplace = true;
                else if (opt == COPY_ATTRIBUTES)
                    hasCopyAttrs = true;
            }
            Entry eDst = getEntry(dst);
            if (eDst != null) {
                if (!hasReplace)
                    throw new FileAlreadyExistsException(getString(dst));
            } else {
                checkParents(dst);
            }
            // copy eSrc entry and change name
            Entry u = supportPosix ?
                new PosixEntry((PosixEntry)eSrc, Entry.COPY) :
                new Entry(eSrc, Entry.COPY);
            u.name(dst);
            if (eSrc.type == Entry.NEW || eSrc.type == Entry.FILECH) {
                u.type = eSrc.type;    // make it the same type
                if (deletesrc) {       // if it's a "rename", take the data
                    u.bytes = eSrc.bytes;
                    u.file = eSrc.file;
                } else {               // if it's not "rename", copy the data
                    if (eSrc.bytes != null)
                        u.bytes = Arrays.copyOf(eSrc.bytes, eSrc.bytes.length);
                    else if (eSrc.file != null) {
                        u.file = getTempPathForEntry(null);
                        Files.copy(eSrc.file, u.file, REPLACE_EXISTING);
                    }
                }
            } else if (eSrc.type == Entry.CEN && eSrc.method != defaultCompressionMethod) {

                /**
                 * We are copying a file within the same Zip file using a
                 * different compression method.
                 */
                try (InputStream in = newInputStream(src);
                     OutputStream out = newOutputStream(dst,
                             CREATE, TRUNCATE_EXISTING, WRITE)) {
                    in.transferTo(out);
                }
                u = getEntry(dst);
            }

            if (!hasCopyAttrs)
                u.mtime = u.atime= u.ctime = System.currentTimeMillis();
            update(u);
            if (deletesrc)
                updateDelete(eSrc);
        } finally {
            endWrite();
        }
    }

    // Returns an output stream for writing the contents into the specified
    // entry.
    OutputStream newOutputStream(byte[] path, OpenOption... options)
        throws IOException
    {
        checkWritable();
        boolean hasCreateNew = false;
        boolean hasCreate = false;
        boolean hasAppend = false;
        boolean hasTruncate = false;
        for (OpenOption opt : options) {
            if (opt == READ)
                throw new IllegalArgumentException("READ not allowed");
            if (opt == CREATE_NEW)
                hasCreateNew = true;
            if (opt == CREATE)
                hasCreate = true;
            if (opt == APPEND)
                hasAppend = true;
            if (opt == TRUNCATE_EXISTING)
                hasTruncate = true;
        }
        if (hasAppend && hasTruncate)
            throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
        beginRead();                 // only need a readlock, the "update()" will
        try {                        // try to obtain a writelock when the os is
            ensureOpen();            // being closed.
            Entry e = getEntry(path);
            if (e != null) {
                if (e.isDir() || hasCreateNew)
                    throw new FileAlreadyExistsException(getString(path));
                if (hasAppend) {
                    OutputStream os = getOutputStream(new Entry(e, Entry.NEW));
                    try (InputStream is = getInputStream(e)) {
                        is.transferTo(os);
                    }
                    return os;
                }
                return getOutputStream(supportPosix ?
                    new PosixEntry((PosixEntry)e, Entry.NEW, defaultCompressionMethod)
                        : new Entry(e, Entry.NEW, defaultCompressionMethod));
            } else {
                if (!hasCreate && !hasCreateNew)
                    throw new NoSuchFileException(getString(path));
                checkParents(path);
                return getOutputStream(supportPosix ?
                    new PosixEntry(path, Entry.NEW, false, defaultCompressionMethod) :
                    new Entry(path, Entry.NEW, false, defaultCompressionMethod));
            }
        } finally {
            endRead();
        }
    }

    // Returns an input stream for reading the contents of the specified
    // file entry.
    InputStream newInputStream(byte[] path) throws IOException {
        beginRead();
        try {
            ensureOpen();
            Entry e = getEntry(path);
            if (e == null)
                throw new NoSuchFileException(getString(path));
            if (e.isDir())
                throw new FileSystemException(getString(path), null, "is a directory");
            return getInputStream(e);
        } finally {
            endRead();
        }
    }

    private void checkOptions(Set<? extends OpenOption> options) {
        // check for options of null type and option is an instance of StandardOpenOption
        for (OpenOption option : options) {
            if (option == null)
                throw new NullPointerException();
            if (!(option instanceof StandardOpenOption))
                throw new IllegalArgumentException();
        }
        if (options.contains(APPEND) && options.contains(TRUNCATE_EXISTING))
            throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
    }

    // Returns an output SeekableByteChannel for either
    // (1) writing the contents of a new entry, if the entry doesn't exist, or
    // (2) updating/replacing the contents of an existing entry.
    // Note: The content of the channel is not compressed until the
    // channel is closed
    private class EntryOutputChannel extends ByteArrayChannel {
        final Entry e;

        EntryOutputChannel(Entry e) {
            super(e.size > 0? (int)e.size : 8192, false);
            this.e = e;
            if (e.mtime == -1)
                e.mtime = System.currentTimeMillis();
            if (e.method == -1)
                e.method = defaultCompressionMethod;
            // store size, compressed size, and crc-32 in datadescriptor
            e.flag = FLAG_DATADESCR;
            if (zc.isUTF8())
                e.flag |= FLAG_USE_UTF8;
        }

        @Override
        public void close() throws IOException {
            super.beginWrite();
            try {
                if (!isOpen())
                    return;
                // will update the entry
                try (OutputStream os = getOutputStream(e)) {
                    os.write(toByteArray());
                }
                super.close();
            } finally {
                super.endWrite();
            }
        }
    }

    // Returns a Writable/ReadByteChannel for now. Might consider to use
    // newFileChannel() instead, which dump the entry data into a regular
    // file on the default file system and create a FileChannel on top of it.
    SeekableByteChannel newByteChannel(byte[] path,
                                       Set<? extends OpenOption> options,
                                       FileAttribute<?>... attrs)
        throws IOException
    {
        checkOptions(options);
        if (options.contains(StandardOpenOption.WRITE) ||
            options.contains(StandardOpenOption.APPEND)) {
            checkWritable();
            beginRead();    // only need a read lock, the "update()" will obtain
                            // the write lock when the channel is closed
            ensureOpen();
            try {
                Entry e = getEntry(path);
                if (e != null) {
                    if (e.isDir() || options.contains(CREATE_NEW))
                        throw new FileAlreadyExistsException(getString(path));
                    SeekableByteChannel sbc =
                            new EntryOutputChannel(supportPosix ?
                                new PosixEntry((PosixEntry)e, Entry.NEW) :
                                new Entry(e, Entry.NEW));
                    if (options.contains(APPEND)) {
                        try (InputStream is = getInputStream(e)) {  // copyover
                            byte[] buf = new byte[8192];
                            ByteBuffer bb = ByteBuffer.wrap(buf);
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                bb.position(0);
                                bb.limit(n);
                                sbc.write(bb);
                            }
                        }
                    }
                    return sbc;
                }
                if (!options.contains(CREATE) && !options.contains(CREATE_NEW))
                    throw new NoSuchFileException(getString(path));
                checkParents(path);
                return new EntryOutputChannel(
                    supportPosix ?
                        new PosixEntry(path, Entry.NEW, false, defaultCompressionMethod, attrs) :
                        new Entry(path, Entry.NEW, false, defaultCompressionMethod, attrs));
            } finally {
                endRead();
            }
        } else {
            beginRead();
            try {
                ensureOpen();
                Entry e = getEntry(path);
                if (e == null || e.isDir())
                    throw new NoSuchFileException(getString(path));
                try (InputStream is = getInputStream(e)) {
                    // TBD: if (e.size < NNNNN);
                    return new ByteArrayChannel(is.readAllBytes(), true);
                }
            } finally {
                endRead();
            }
        }
    }

    // Returns a FileChannel of the specified entry.
    //
    // This implementation creates a temporary file on the default file system,
    // copy the entry data into it if the entry exists, and then create a
    // FileChannel on top of it.
    FileChannel newFileChannel(byte[] path,
                               Set<? extends OpenOption> options,
                               FileAttribute<?>... attrs)
        throws IOException
    {
        checkOptions(options);
        final  boolean forWrite = (options.contains(StandardOpenOption.WRITE) ||
                                   options.contains(StandardOpenOption.APPEND));
        beginRead();
        try {
            ensureOpen();
            Entry e = getEntry(path);
            if (forWrite) {
                checkWritable();
                if (e == null) {
                    if (!options.contains(StandardOpenOption.CREATE) &&
                        !options.contains(StandardOpenOption.CREATE_NEW)) {
                        throw new NoSuchFileException(getString(path));
                    }
                } else {
                    if (options.contains(StandardOpenOption.CREATE_NEW)) {
                        throw new FileAlreadyExistsException(getString(path));
                    }
                    if (e.isDir())
                        throw new FileAlreadyExistsException("directory <"
                            + getString(path) + "> exists");
                }
                options = new HashSet<>(options);
                options.remove(StandardOpenOption.CREATE_NEW); // for tmpfile
            } else if (e == null || e.isDir()) {
                throw new NoSuchFileException(getString(path));
            }

            final boolean isFCH = (e != null && e.type == Entry.FILECH);
            final Path tmpfile = isFCH ? e.file : getTempPathForEntry(path);
            final FileChannel fch = tmpfile.getFileSystem()
                                           .provider()
                                           .newFileChannel(tmpfile, options, attrs);
            final Entry u = isFCH ? e : (
                supportPosix ?
                new PosixEntry(path, tmpfile, Entry.FILECH, attrs) :
                new Entry(path, tmpfile, Entry.FILECH, attrs));
            if (forWrite) {
                u.flag = FLAG_DATADESCR;
                u.method = defaultCompressionMethod;
            }
            // is there a better way to hook into the FileChannel's close method?
            return new FileChannel() {
                public int write(ByteBuffer src) throws IOException {
                    return fch.write(src);
                }
                public long write(ByteBuffer[] srcs, int offset, int length)
                    throws IOException
                {
                    return fch.write(srcs, offset, length);
                }
                public long position() throws IOException {
                    return fch.position();
                }
                public FileChannel position(long newPosition)
                    throws IOException
                {
                    fch.position(newPosition);
                    return this;
                }
                public long size() throws IOException {
                    return fch.size();
                }
                public FileChannel truncate(long size)
                    throws IOException
                {
                    fch.truncate(size);
                    return this;
                }
                public void force(boolean metaData)
                    throws IOException
                {
                    fch.force(metaData);
                }
                public long transferTo(long position, long count,
                                       WritableByteChannel target)
                    throws IOException
                {
                    return fch.transferTo(position, count, target);
                }
                public long transferFrom(ReadableByteChannel src,
                                         long position, long count)
                    throws IOException
                {
                    return fch.transferFrom(src, position, count);
                }
                public int read(ByteBuffer dst) throws IOException {
                    return fch.read(dst);
                }
                public int read(ByteBuffer dst, long position)
                    throws IOException
                {
                    return fch.read(dst, position);
                }
                public long read(ByteBuffer[] dsts, int offset, int length)
                    throws IOException
                {
                    return fch.read(dsts, offset, length);
                }
                public int write(ByteBuffer src, long position)
                    throws IOException
                {
                   return fch.write(src, position);
                }
                public MappedByteBuffer map(MapMode mode,
                                            long position, long size)
                {
                    throw new UnsupportedOperationException();
                }
                public FileLock lock(long position, long size, boolean shared)
                    throws IOException
                {
                    return fch.lock(position, size, shared);
                }
                public FileLock tryLock(long position, long size, boolean shared)
                    throws IOException
                {
                    return fch.tryLock(position, size, shared);
                }
                protected void implCloseChannel() throws IOException {
                    fch.close();
                    if (forWrite) {
                        u.mtime = System.currentTimeMillis();
                        u.size = Files.size(u.file);
                        update(u);
                    } else {
                        if (!isFCH)    // if this is a new fch for reading
                            removeTempPathForEntry(tmpfile);
                    }
               }
            };
        } finally {
            endRead();
        }
    }

    // the outstanding input streams that need to be closed
    private Set<InputStream> streams =
        Collections.synchronizedSet(new HashSet<>());

    private final Set<Path> tmppaths = Collections.synchronizedSet(new HashSet<>());
    private Path getTempPathForEntry(byte[] path) throws IOException {
        Path tmpPath = createTempFileInSameDirectoryAs(zfpath);
        if (path != null) {
            Entry e = getEntry(path);
            if (e != null) {
                try (InputStream is = newInputStream(path)) {
                    Files.copy(is, tmpPath, REPLACE_EXISTING);
                }
            }
        }
        return tmpPath;
    }

    private void removeTempPathForEntry(Path path) throws IOException {
        Files.delete(path);
        tmppaths.remove(path);
    }

    // check if all parents really exist. ZIP spec does not require
    // the existence of any "parent directory".
    private void checkParents(byte[] path) throws IOException {
        beginRead();
        try {
            while ((path = getParent(path)) != null &&
                    path != ROOTPATH) {
                if (!inodes.containsKey(IndexNode.keyOf(path))) {
                    throw new NoSuchFileException(getString(path));
                }
            }
        } finally {
            endRead();
        }
    }

    private static byte[] getParent(byte[] path) {
        int off = getParentOff(path);
        if (off <= 1)
            return ROOTPATH;
        return Arrays.copyOf(path, off);
    }

    private static int getParentOff(byte[] path) {
        int off = path.length - 1;
        if (off > 0 && path[off] == '/')  // isDirectory
            off--;
        while (off > 0 && path[off] != '/') { off--; }
        return off;
    }

    private void beginWrite() {
        rwlock.writeLock().lock();
    }

    private void endWrite() {
        rwlock.writeLock().unlock();
    }

    private void beginRead() {
        rwlock.readLock().lock();
    }

    private void endRead() {
        rwlock.readLock().unlock();
    }

    ///////////////////////////////////////////////////////////////////

    private volatile boolean isOpen = true;
    private final SeekableByteChannel ch; // channel to the zipfile
    final byte[]  cen;     // CEN & ENDHDR
    private END  end;
    private long locpos;   // position of first LOC header (usually 0)

    private final ReadWriteLock rwlock = new ReentrantReadWriteLock();

    // name -> pos (in cen), IndexNode itself can be used as a "key"
    private LinkedHashMap<IndexNode, IndexNode> inodes;

    final byte[] getBytes(String name) {
        return zc.getBytes(name);
    }

    final String getString(byte[] name) {
        return zc.toString(name);
    }

    @SuppressWarnings("removal")
    protected void finalize() throws IOException {
        close();
    }

    /**
     * Reads len bytes of data at the given file position into the given byte array
     * starting at the given array offset. The method blocks until len bytes have been
     * read, end of stream is detected, or an exception is thrown. Returns the total
     * number of bytes read.
     */
    final long readNBytesAt(byte[] buf, int off, long len, long pos) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.position(off);
        bb.limit((int)(off + len));

        long totalRead = 0;
        while (totalRead < len) {
            int n = readAt(bb, pos);
            if (n < 0) {
                break;
            }
            pos += n;
            totalRead +=n;
        }
        return totalRead;
    }

    private int readAt(ByteBuffer bb, long pos) throws IOException {
        if (ch instanceof FileChannel fc) {
            return fc.read(bb, pos);
        } else {
            synchronized (ch) {
                return ch.position(pos).read(bb);
            }
        }
    }

    // Searches for end of central directory (END) header. The contents of
    // the END header will be read and placed in endbuf. Returns the file
    // position of the END header, otherwise returns -1 if the END header
    // was not found or an error occurred.
    private END findEND() throws IOException {
        byte[] buf = new byte[READBLOCKSZ];
        long ziplen = ch.size();
        long minHDR = (ziplen - END_MAXLEN) > 0 ? ziplen - END_MAXLEN : 0;
        long minPos = minHDR - (buf.length - ENDHDR);

        for (long pos = ziplen - buf.length; pos >= minPos; pos -= (buf.length - ENDHDR)) {
            int off = 0;
            if (pos < 0) {
                // Pretend there are some NUL bytes before start of file
                off = (int)-pos;
                Arrays.fill(buf, 0, off, (byte)0);
            }
            int len = buf.length - off;
            if (readNBytesAt(buf, off, len, pos + off) != len)
                throw new ZipException("zip END header not found");

            // Now scan the block backwards for END header signature
            for (int i = buf.length - ENDHDR; i >= 0; i--) {
                if (buf[i]   == (byte)'P'    &&
                    buf[i+1] == (byte)'K'    &&
                    buf[i+2] == (byte)'\005' &&
                    buf[i+3] == (byte)'\006' &&
                    (pos + i + ENDHDR + ENDCOM(buf, i) == ziplen)) {
                    // Found END header
                    buf = Arrays.copyOfRange(buf, i, i + ENDHDR);
                    END end = new END();
                    // end.endsub = ENDSUB(buf); // not used
                    end.centot = ENDTOT(buf);
                    end.cenlen = ENDSIZ(buf);
                    end.cenoff = ENDOFF(buf);
                    // end.comlen = ENDCOM(buf); // not used
                    end.endpos = pos + i;
                    // try if there is zip64 end;
                    byte[] loc64 = new byte[ZIP64_LOCHDR];
                    if (end.endpos < ZIP64_LOCHDR ||
                        readNBytesAt(loc64, 0, loc64.length, end.endpos - ZIP64_LOCHDR)
                        != loc64.length ||
                        !locator64SigAt(loc64, 0)) {
                        return end;
                    }
                    long end64pos = ZIP64_LOCOFF(loc64);
                    byte[] end64buf = new byte[ZIP64_ENDHDR];
                    if (readNBytesAt(end64buf, 0, end64buf.length, end64pos)
                        != end64buf.length ||
                        !end64SigAt(end64buf, 0)) {
                        return end;
                    }
                    // end64 found,
                    long cenlen64 = ZIP64_ENDSIZ(end64buf);
                    long cenoff64 = ZIP64_ENDOFF(end64buf);
                    long centot64 = ZIP64_ENDTOT(end64buf);
                    // double-check
                    if (cenlen64 != end.cenlen && end.cenlen != ZIP64_MINVAL ||
                        cenoff64 != end.cenoff && end.cenoff != ZIP64_MINVAL ||
                        centot64 != end.centot && end.centot != ZIP64_MINVAL32) {
                        return end;
                    }
                    // to use the end64 values
                    end.cenlen = cenlen64;
                    end.cenoff = cenoff64;
                    end.centot = (int)centot64; // assume total < 2g
                    end.endpos = end64pos;
                    return end;
                }
            }
        }
        throw new ZipException("zip END header not found");
    }

    private void makeParentDirs(IndexNode node, IndexNode root) {
        IndexNode parent;
        ParentLookup lookup = new ParentLookup();
        while (true) {
            int off = getParentOff(node.name);
            // parent is root
            if (off <= 1) {
                node.sibling = root.child;
                root.child = node;
                break;
            }
            // parent exists
            lookup = lookup.as(node.name, off);
            parent = inodes.get(lookup);
            if (parent != null) {
                node.sibling = parent.child;
                parent.child = node;
                break;
            }
            // parent does not exist, add new pseudo directory entry
            parent = new IndexNode(Arrays.copyOf(node.name, off), true);
            inodes.put(parent, parent);
            node.sibling = parent.child;
            parent.child = node;
            node = parent;
        }
    }

    // ZIP directory has two issues:
    // (1) ZIP spec does not require the ZIP file to include
    //     directory entry
    // (2) all entries are not stored/organized in a "tree"
    //     structure.
    // A possible solution is to build the node tree ourself as
    // implemented below.
    private void buildNodeTree() {
        beginWrite();
        try {
            IndexNode root = inodes.remove(LOOKUPKEY.as(ROOTPATH));
            if (root == null) {
                root = new IndexNode(ROOTPATH, true);
            }
            IndexNode[] nodes = inodes.values().toArray(new IndexNode[0]);
            inodes.put(root, root);
            for (IndexNode node : nodes) {
                makeParentDirs(node, root);
            }
        } finally {
            endWrite();
        }
    }

    private void removeFromTree(IndexNode inode) {
        IndexNode parent = inodes.get(LOOKUPKEY.as(getParent(inode.name)));
        IndexNode child = parent.child;
        if (child.equals(inode)) {
            parent.child = child.sibling;
        } else {
            IndexNode last = child;
            while ((child = child.sibling) != null) {
                if (child.equals(inode)) {
                    last.sibling = child.sibling;
                    break;
                } else {
                    last = child;
                }
            }
        }
    }

    /**
     * If a version property has been specified and the file represents a multi-release JAR,
     * determine the requested runtime version and initialize the ZipFileSystem instance accordingly.
     *
     * Checks if the Zip File System property "releaseVersion" has been specified. If it has,
     * use its value to determine the requested version. If not use the value of the "multi-release" property.
     */
    private Optional<Integer> determineReleaseVersion(Map<String, ?> env) throws IOException {
        Object o = env.containsKey(PROPERTY_RELEASE_VERSION) ?
            env.get(PROPERTY_RELEASE_VERSION) :
            env.get(PROPERTY_MULTI_RELEASE);

        if (o == null || !isMultiReleaseJar()) {
            return Optional.empty();
        }
        int version = switch (o) {
            case String s when s.equals("runtime") -> Runtime.version().feature();
            case String s when s.matches("^[1-9][0-9]*$") -> Version.parse(s).feature();
            case Integer i -> Version.parse(i.toString()).feature();
            case Version v -> v.feature();
            case String s -> throw new IllegalArgumentException("Invalid runtime version: " + s);
            default -> throw new IllegalArgumentException("env parameter must be String, " +
                    "Integer, or Version");
        };
        return Optional.of(Math.max(version, 0));
    }

    /**
     * Returns true if the Manifest main attribute "Multi-Release" is set to true; false otherwise.
     */
    private boolean isMultiReleaseJar() throws IOException {
        try (InputStream is = newInputStream(getBytes("/META-INF/MANIFEST.MF"))) {
            String multiRelease = new Manifest(is).getMainAttributes()
                .getValue(Attributes.Name.MULTI_RELEASE);
            return "true".equalsIgnoreCase(multiRelease);
        } catch (NoSuchFileException x) {
            return false;
        }
    }

    /**
     * Create a map of aliases for versioned entries, for example:
     *   version/PackagePrivate.class -> META-INF/versions/9/version/PackagePrivate.class
     *   version/PackagePrivate.java -> META-INF/versions/9/version/PackagePrivate.java
     *   version/Version.class -> META-INF/versions/10/version/Version.class
     *   version/Version.java -> META-INF/versions/10/version/Version.java
     *
     * Then wrap the map in a function that getEntry can use to override root
     * entry lookup for entries that have corresponding versioned entries.
     */
    private Function<byte[], byte[]> createVersionedLinks(int version) {
        IndexNode verdir = getInode(getBytes("/META-INF/versions"));
        // nothing to do, if no /META-INF/versions
        if (verdir == null) {
            return Function.identity();
        }
        // otherwise, create a map and for each META-INF/versions/{n} directory
        // put all the leaf inodes, i.e. entries, into the alias map
        // possibly shadowing lower versioned entries
        HashMap<IndexNode, byte[]> aliasMap = new HashMap<>();
        getVersionMap(version, verdir).values().forEach(versionNode ->
            walk(versionNode.child, entryNode ->
                aliasMap.put(
                    getOrCreateInode(getRootName(entryNode, versionNode), entryNode.isdir),
                    entryNode.name))
        );
        return path -> aliasMap.getOrDefault(IndexNode.keyOf(path), path);
    }

    /**
     * Package-private accessor to entry alias map used by ZipPath.
     */
    byte[] lookupPath(byte[] resolvedPath) {
        return entryLookup.apply(resolvedPath);
    }

    /**
     * Create a sorted version map of version -> inode, for inodes <= max version.
     *   9 -> META-INF/versions/9
     *  10 -> META-INF/versions/10
     */
    private TreeMap<Integer, IndexNode> getVersionMap(int version, IndexNode metaInfVersions) {
        TreeMap<Integer,IndexNode> map = new TreeMap<>();
        IndexNode child = metaInfVersions.child;
        while (child != null) {
            Integer key = getVersion(child, metaInfVersions);
            if (key != null && key <= version) {
                map.put(key, child);
            }
            child = child.sibling;
        }
        return map;
    }

    /**
     * Extract the integer version number -- META-INF/versions/9 returns 9.
     */
    private Integer getVersion(IndexNode inode, IndexNode metaInfVersions) {
        try {
            byte[] fullName = inode.name;
            return Integer.parseInt(getString(Arrays
                .copyOfRange(fullName, metaInfVersions.name.length + 1, fullName.length)));
        } catch (NumberFormatException x) {
            // ignore this even though it might indicate issues with the JAR structure
            return null;
        }
    }

    /**
     * Walk the IndexNode tree processing all leaf nodes.
     */
    private void walk(IndexNode inode, Consumer<IndexNode> consumer) {
        if (inode == null) return;
        if (inode.isDir()) {
            walk(inode.child, consumer);
        } else {
            consumer.accept(inode);
        }
        walk(inode.sibling, consumer);
    }

    /**
     * Extract the root name from a versioned entry name.
     * E.g. given inode 'META-INF/versions/9/foo/bar.class'
     * and prefix 'META-INF/versions/9/' returns 'foo/bar.class'.
     */
    private byte[] getRootName(IndexNode inode, IndexNode prefix) {
        byte[] fullName = inode.name;
        return Arrays.copyOfRange(fullName, prefix.name.length, fullName.length);
    }

    // Reads zip file central directory. Returns the file position of first
    // CEN header, otherwise returns -1 if an error occurred. If zip->msg != NULL
    // then the error was a zip format error and zip->msg has the error text.
    // Always pass in -1 for knownTotal; it's used for a recursive call.
    private byte[] initCEN() throws IOException {
        end = findEND();
        if (end.endpos == 0) {
            inodes = new LinkedHashMap<>(10);
            locpos = 0;
            buildNodeTree();
            return null;         // only END header present
        }
        if (end.cenlen > end.endpos)
            throw new ZipException("invalid END header (bad central directory size)");
        long cenpos = end.endpos - end.cenlen;     // position of CEN table

        // Get position of first local file (LOC) header, taking into
        // account that there may be a stub prefixed to the zip file.
        locpos = cenpos - end.cenoff;
        if (locpos < 0)
            throw new ZipException("invalid END header (bad central directory offset)");

        // read in the CEN and END
        byte[] cen = new byte[(int)(end.cenlen + ENDHDR)];
        if (readNBytesAt(cen, 0, cen.length, cenpos) != end.cenlen + ENDHDR) {
            throw new ZipException("read CEN tables failed");
        }
        // Iterate through the entries in the central directory
        inodes = LinkedHashMap.newLinkedHashMap(end.centot + 1);
        int pos = 0;
        int limit = cen.length - ENDHDR;
        while (pos < limit) {
            if (!cenSigAt(cen, pos))
                throw new ZipException("invalid CEN header (bad signature)");
            int method = CENHOW(cen, pos);
            int nlen   = CENNAM(cen, pos);
            int elen   = CENEXT(cen, pos);
            int clen   = CENCOM(cen, pos);
            int flag   = CENFLG(cen, pos);
            long csize = CENSIZ(cen, pos);
            long size  = CENLEN(cen, pos);
            long locoff = CENOFF(cen, pos);
            int diskNo = CENDSK(cen, pos);
            if ((flag & 1) != 0) {
                throw new ZipException("invalid CEN header (encrypted entry)");
            }
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                throw new ZipException("invalid CEN header (unsupported compression method: " + method + ")");
            }
            long headerSize = (long)CENHDR + nlen + clen + elen;
            // CEN header size + name length + comment length + extra length
            // should not exceed 65,535 bytes per the PKWare APP.NOTE
            // 4.4.10, 4.4.11, & 4.4.12.  Also check that current CEN header will
            // not exceed the length of the CEN array
            if (headerSize > 0xFFFF || pos + headerSize > limit) {
                zerror("invalid CEN header (bad header size)");
            }
            if (elen > 0) {
                checkExtraFields(cen, pos, size, csize, locoff, diskNo,
                        pos + CENHDR + nlen, elen);
            } else if (elen == 0 && (size == ZIP64_MINVAL || csize == ZIP64_MINVAL ||
                    locoff == ZIP64_MINVAL || diskNo == ZIP64_MINVAL32)) {
                throw new ZipException("Invalid CEN header (invalid zip64 extra len size)");
            }
            IndexNode inode = new IndexNode(cen, pos, nlen);
            if (inode.pathHasDotOrDotDot()) {
                throw new ZipException("ZIP file can't be opened as a file system " +
                        "because entry \"" + inode.nameAsString() + "\" has a '.' or '..' element in its name");
            }
            inodes.put(inode, inode);
            if (zc.isUTF8() || (flag & FLAG_USE_UTF8) != 0) {
                checkUTF8(inode.name);
            } else {
                checkEncoding(inode.name);
            }
            // skip ext and comment
            pos += (CENHDR + nlen + elen + clen);
        }
        if (pos + ENDHDR != cen.length) {
            throw new ZipException("invalid CEN header (bad header size)");
        }
        buildNodeTree();
        return cen;
    }

    /**
     * Validate the Zip64 Extra block fields
     * @param cen CEN array
     * @param cenPos starting offset in the CEN for the Extra field
     * @param size CEN size value
     * @param csize CEN csize value
     * @param locoff CEN LOC offset value
     * @param diskNo CEN Disk number value
     * @param startingOffset Extra Field starting offset within the CEN
     * @param extraFieldLen Length of this Extra field
     * @throws ZipException  If an error occurs validating the Zip64 Extra
     * block
     */
    private void checkExtraFields( byte[] cen, int cenPos, long size, long csize,
                                   long locoff, int diskNo, int startingOffset,
                                   int extraFieldLen) throws ZipException {
        // Extra field Length cannot exceed 65,535 bytes per the PKWare
        // APP.note 4.4.11
        if (extraFieldLen > 0xFFFF) {
            zerror("invalid extra field length");
        }
        // CEN Offset where this Extra field ends
        int extraEndOffset = startingOffset + extraFieldLen;
        if (extraEndOffset > cen.length - ENDHDR) {
            zerror("Invalid CEN header (extra data field size too long)");
        }
        int currentOffset = startingOffset;
        // Walk through each Extra Header. Each Extra Header Must consist of:
        //       Header ID - 2 bytes
        //       Data Size - 2 bytes:
        while (currentOffset + Integer.BYTES <= extraEndOffset) {
            int tag = SH(cen, currentOffset);
            currentOffset += Short.BYTES;

            int tagBlockSize = SH(cen, currentOffset);
            currentOffset += Short.BYTES;
            long tagBlockEndingOffset = (long)currentOffset + tagBlockSize;

            //  The ending offset for this tag block should not go past the
            //  offset for the end of the extra field
            if (tagBlockEndingOffset > extraEndOffset) {
                zerror(String.format(
                        "Invalid CEN header (invalid extra data field size for " +
                                "tag: 0x%04x at %d)",
                        tag, cenPos));
            }

            if (tag == EXTID_ZIP64) {
                checkZip64ExtraFieldValues(cen, currentOffset, tagBlockSize,
                        csize, size, locoff, diskNo);
            }
            currentOffset += tagBlockSize;
        }
    }

    /**
     * Validate the Zip64 Extended Information Extra Field (0x0001) block
     * size; that the uncompressed size, compressed size field and LOC
     * offset fields are not negative. Also make sure the field exists if
     * the CEN header field is set to 0xFFFFFFFF.
     * Note:  As we do not use the Starting disk number field,
     * we will not validate its value
     * @param cen CEN array
     * @param off the starting offset for the Zip64 field value
     * @param blockSize the size of the Zip64 Extended Extra Field
     * @param csize CEN header compressed size value
     * @param size CEN header uncompressed size value
     * @param locoff CEN header LOC offset
     * @param diskNo CEN header Disk Number
     * @throws ZipException if an error occurs
     */
    private void checkZip64ExtraFieldValues(byte[] cen, int off, int blockSize, long csize,
                                            long size, long locoff, int diskNo)
            throws ZipException {
        // if EXTID_ZIP64 blocksize == 0, which may occur with some older
        // versions of Apache Ant and Commons Compress, validate csize and size
        // to make sure neither field == ZIP64_MAGICVAL
        if (blockSize == 0) {
            if (csize == ZIP64_MINVAL || size == ZIP64_MINVAL ||
                    locoff == ZIP64_MINVAL || diskNo == ZIP64_MINVAL32) {
                zerror("Invalid CEN header (invalid zip64 extra data field size)");
            }
            // Only validate the EXTID_ZIP64 data if the block size > 0
            return;
        }
        // Validate the Zip64 Extended Information Extra Field (0x0001)
        // length.
        if (!isZip64ExtBlockSizeValid(blockSize, csize, size, locoff, diskNo)) {
            zerror("Invalid CEN header (invalid zip64 extra data field size)");
        }
        // Check the uncompressed size is not negative
        if (size == ZIP64_MINVAL) {
            if (blockSize >= Long.BYTES) {
                if (LL(cen, off) < 0) {
                    zerror("Invalid zip64 extra block size value");
                }
                off += Long.BYTES;
                blockSize -= Long.BYTES;
            } else {
                zerror("Invalid Zip64 extra block, missing size");
            }
        }
        // Check the compressed size is not negative
        if (csize == ZIP64_MINVAL) {
            if (blockSize >= Long.BYTES) {
                if (LL(cen, off) < 0) {
                    zerror("Invalid zip64 extra block compressed size value");
                }
                off += Long.BYTES;
                blockSize -= Long.BYTES;
            } else {
                zerror("Invalid Zip64 extra block, missing compressed size");
            }
        }
        // Check the LOC offset is not negative
        if (locoff == ZIP64_MINVAL) {
            if (blockSize >= Long.BYTES) {
                if (LL(cen, off) < 0) {
                    zerror("Invalid zip64 extra block LOC OFFSET value");
                }
                // Note: We do not need to adjust the following fields as
                // this is the last field we are leveraging
                // off += Long.BYTES;
                // blockSize -= Long.BYTES;
            } else {
                zerror("Invalid Zip64 extra block, missing LOC offset value");
            }
        }
    }

    /**
     * Validate the size and contents of a Zip64 extended information field
     * The order of the Zip64 fields is fixed, but the fields MUST
     * only appear if the corresponding LOC or CEN field is set to 0xFFFF:
     * or 0xFFFFFFFF:
     * Uncompressed Size - 8 bytes
     * Compressed Size   - 8 bytes
     * LOC Header offset - 8 bytes
     * Disk Start Number - 4 bytes
     * See PKWare APP.Note Section 4.5.3 for more details
     *
     * @param blockSize the Zip64 Extended Information Extra Field size
     * @param csize CEN header compressed size value
     * @param size CEN header uncompressed size value
     * @param locoff CEN header LOC offset
     * @param diskNo CEN header Disk Number
     * @return true if the extra block size is valid; false otherwise
     */
    private static boolean isZip64ExtBlockSizeValid(int blockSize, long csize,
                                                    long size, long locoff,
                                                    int diskNo) {
        int expectedBlockSize =
                (csize == ZIP64_MINVAL ? Long.BYTES : 0) +
                        (size == ZIP64_MINVAL ? Long.BYTES : 0) +
                        (locoff == ZIP64_MINVAL ? Long.BYTES : 0) +
                        (diskNo == ZIP64_MINVAL32 ? Integer.BYTES : 0);
        return expectedBlockSize == blockSize;
    }

    private  final void checkUTF8(byte[] a) throws ZipException {
        try {
            int end = a.length;
            int pos = 0;
            while (pos < end) {
                // ASCII fast-path: When checking that a range of bytes is
                // valid UTF-8, we can avoid some allocation by skipping
                // past bytes in the 0-127 range
                if (a[pos] < 0) {
                    zc.toString(Arrays.copyOfRange(a, pos, a.length));
                    break;
                }
                pos++;
            }
        } catch(Exception e) {
            throw new ZipException("invalid CEN header (bad entry name)");
        }
    }

    private static void zerror(String msg) throws ZipException {
        throw new ZipException(msg);
    }

    private final void checkEncoding( byte[] a) throws ZipException {
        try {
            zc.toString(a);
        } catch(Exception e) {
            throw new ZipException("invalid CEN header (bad entry name)");
        }
    }

    private void ensureOpen() {
        if (!isOpen)
            throw new ClosedFileSystemException();
    }

    // Creates a new empty temporary file in the same directory as the
    // specified file.  A variant of Files.createTempFile.
    private Path createTempFileInSameDirectoryAs(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Path dir = (parent == null) ? path.getFileSystem().getPath(".") : parent;
        Path tmpPath = Files.createTempFile(dir, "zipfstmp", null);
        tmppaths.add(tmpPath);
        return tmpPath;
    }

    ////////////////////update & sync //////////////////////////////////////

    private boolean hasUpdate = false;

    // shared key. consumer guarantees the "writeLock" before use it.
    private final IndexNode LOOKUPKEY = new IndexNode(null, -1);

    private void updateDelete(IndexNode inode) {
        beginWrite();
        try {
            removeFromTree(inode);
            inodes.remove(inode);
            hasUpdate = true;
        } finally {
             endWrite();
        }
    }

    private void update(Entry e) {
        beginWrite();
        try {
            IndexNode old = inodes.put(e, e);
            if (old != null) {
                removeFromTree(old);
            }
            if (e.type == Entry.NEW || e.type == Entry.FILECH || e.type == Entry.COPY) {
                IndexNode parent = inodes.get(LOOKUPKEY.as(getParent(e.name)));
                e.sibling = parent.child;
                parent.child = e;
            }
            hasUpdate = true;
        } finally {
            endWrite();
        }
    }

    // copy over the whole LOC entry (header if necessary, data and ext) from
    // old zip to the new one.
    private long copyLOCEntry(Entry e, boolean updateHeader,
                              OutputStream os,
                              long written, byte[] buf)
        throws IOException
    {
        long locoff = e.locoff;  // where to read
        e.locoff = written;      // update the e.locoff with new value

        // calculate the size need to write out
        long size = 0;
        //  if there is A ext
        if ((e.flag & FLAG_DATADESCR) != 0) {
            if (e.size >= ZIP64_MINVAL || e.csize >= ZIP64_MINVAL)
                size = 24;
            else
                size = 16;
        }
        // read loc, use the original loc.elen/nlen
        //
        // an extra byte after loc is read, which should be the first byte of the
        // 'name' field of the loc. if this byte is '/', which means the original
        // entry has an absolute path in original zip/jar file, the e.writeLOC()
        // is used to output the loc, in which the leading "/" will be removed
        if (readNBytesAt(buf, 0, LOCHDR + 1 , locoff) != LOCHDR + 1)
            throw new ZipException("loc: reading failed");

        if (updateHeader || LOCNAM(buf) > 0 && buf[LOCHDR] == '/') {
            locoff += LOCHDR + LOCNAM(buf) + LOCEXT(buf);  // skip header
            size += e.csize;
            written = e.writeLOC(os) + size;
        } else {
            os.write(buf, 0, LOCHDR);    // write out the loc header
            locoff += LOCHDR;
            // use e.csize,  LOCSIZ(buf) is zero if FLAG_DATADESCR is on
            // size += LOCNAM(buf) + LOCEXT(buf) + LOCSIZ(buf);
            size += LOCNAM(buf) + LOCEXT(buf) + e.csize;
            written = LOCHDR + size;
        }
        int n;
        while (size > 0 &&
            (n = (int)readNBytesAt(buf, 0, buf.length, locoff)) != -1)
        {
            if (size < n)
                n = (int)size;
            os.write(buf, 0, n);
            size -= n;
            locoff += n;
        }
        return written;
    }

    private long writeEntry(Entry e, OutputStream os)
        throws IOException {

        if (e.bytes == null && e.file == null)    // dir, 0-length data
            return 0;

        long written = 0;
        if (e.method != METHOD_STORED && e.csize > 0 && (e.crc != 0 || e.size == 0)) {
            // pre-compressed entry, write directly to output stream
            writeTo(e, os);
        } else {
            try (OutputStream os2 = (e.method == METHOD_STORED) ?
                    new EntryOutputStreamCRC32(e, os) : new EntryOutputStreamDef(e, os)) {
                writeTo(e, os2);
            }
        }
        written += e.csize;
        if ((e.flag & FLAG_DATADESCR) != 0) {
            written += e.writeEXT(os);
        }
        return written;
    }

    private void writeTo(Entry e, OutputStream os) throws IOException {
        if (e.bytes != null) {
            os.write(e.bytes, 0, e.bytes.length);
        } else if (e.file != null) {
            if (e.type == Entry.NEW || e.type == Entry.FILECH) {
                try (InputStream is = Files.newInputStream(e.file)) {
                    is.transferTo(os);
                }
            }
            Files.delete(e.file);
            tmppaths.remove(e.file);
        }
    }

    // sync the zip file system, if there is any update
    private void sync() throws IOException {
        if (!hasUpdate)
            return;
        PosixFileAttributes attrs = getPosixAttributes(zfpath);
        Path tmpFile = createTempFileInSameDirectoryAs(zfpath);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmpFile, WRITE))) {
            ArrayList<Entry> elist = new ArrayList<>(inodes.size());
            long written = 0;
            byte[] buf = null;
            Entry e;

            final IndexNode manifestInode = inodes.get(
                    IndexNode.keyOf(getBytes("/META-INF/MANIFEST.MF")));
            final Iterator<IndexNode> inodeIterator = inodes.values().iterator();
            boolean manifestProcessed = false;

            // write loc
            while (inodeIterator.hasNext()) {
                final IndexNode inode;

                // write the manifest inode (if any) first so that
                // java.util.jar.JarInputStream can find it
                if (manifestInode == null) {
                    inode = inodeIterator.next();
                } else {
                    if (manifestProcessed) {
                        // advance to next node, filtering out the manifest
                        // which was already written
                        inode = inodeIterator.next();
                        if (inode == manifestInode) {
                            continue;
                        }
                    } else {
                        inode = manifestInode;
                        manifestProcessed = true;
                    }
                }

                if (inode instanceof Entry) {    // an updated inode
                    e = (Entry)inode;
                    try {
                        if (e.type == Entry.COPY) {
                            // entry copy: the only thing changed is the "name"
                            // and "nlen" in LOC header, so we update/rewrite the
                            // LOC in new file and simply copy the rest (data and
                            // ext) without enflating/deflating from the old zip
                            // file LOC entry.
                            if (buf == null)
                                buf = new byte[8192];
                            written += copyLOCEntry(e, true, os, written, buf);
                        } else {                          // NEW, FILECH or CEN
                            e.locoff = written;
                            written += e.writeLOC(os);    // write loc header
                            written += writeEntry(e, os);
                        }
                        elist.add(e);
                    } catch (IOException x) {
                        x.printStackTrace();    // skip any in-accurate entry
                    }
                } else {                        // unchanged inode
                    if (inode.pos == -1) {
                        continue;               // pseudo directory node
                    }
                    if (inode.name.length == 1 && inode.name[0] == '/') {
                        continue;               // no root '/' directory even if it
                                                // exists in original zip/jar file.
                    }
                    e = supportPosix ? new PosixEntry(this, inode) : new Entry(this, inode);
                    try {
                        if (buf == null)
                            buf = new byte[8192];
                        written += copyLOCEntry(e, false, os, written, buf);
                        elist.add(e);
                    } catch (IOException x) {
                        x.printStackTrace();    // skip any wrong entry
                    }
                }
            }

            // now write back the cen and end table
            end.cenoff = written;
            for (Entry entry : elist) {
                written += entry.writeCEN(os);
            }
            end.centot = elist.size();
            end.cenlen = written - end.cenoff;
            end.write(os, written, forceEnd64);
        }
        ch.close();
        Files.delete(zfpath);

        // Set the POSIX permissions of the original Zip File if available
        // before moving the temp file
        if (attrs != null) {
            Files.setPosixFilePermissions(tmpFile, attrs.permissions());
        }
        Files.move(tmpFile, zfpath, REPLACE_EXISTING);
        hasUpdate = false;    // clear
    }

    /**
     * Returns a file's POSIX file attributes.
     * @param path The path to the file
     * @return The POSIX file attributes for the specified file or
     *         null if the POSIX attribute view is not available
     * @throws IOException If an error occurs obtaining the POSIX attributes for
     *                    the specified file
     */
    private PosixFileAttributes getPosixAttributes(Path path) throws IOException {
        try {
            PosixFileAttributeView view =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class);
            // Return if the attribute view is not supported
            if (view == null) {
                return null;
            }
            return view.readAttributes();
        } catch (UnsupportedOperationException e) {
            // PosixFileAttributes not available
            return null;
        }
    }

    private IndexNode getInode(byte[] path) {
        return inodes.get(IndexNode.keyOf(Objects.requireNonNull(entryLookup.apply(path), "path")));
    }

    /**
     * Return the IndexNode from the root tree. If it doesn't exist,
     * it gets created along with all parent directory IndexNodes.
     */
    private IndexNode getOrCreateInode(byte[] path, boolean isdir) {
        IndexNode node = getInode(path);
        // if node exists, return it
        if (node != null) {
            return node;
        }

        // otherwise create new pseudo node and parent directory hierarchy
        node = new IndexNode(path, isdir);
        beginWrite();
        try {
            makeParentDirs(node, Objects.requireNonNull(inodes.get(IndexNode.keyOf(ROOTPATH)), "no root node found"));
            return node;
        } finally {
            endWrite();
        }
    }

    private Entry getEntry(byte[] path) throws IOException {
        IndexNode inode = getInode(path);
        if (inode instanceof Entry)
            return (Entry)inode;
        if (inode == null || inode.pos == -1)
            return null;
        return supportPosix ? new PosixEntry(this, inode): new Entry(this, inode);
    }

    public void deleteFile(byte[] path, boolean failIfNotExists)
        throws IOException
    {
        checkWritable();
        IndexNode inode = getInode(path);
        if (inode == null) {
            if (path != null && path.length == 0)
                throw new ZipException("root directory </> cannot be deleted");
            if (failIfNotExists)
                throw new NoSuchFileException(getString(path));
        } else {
            if (inode.isDir() && inode.child != null)
                throw new DirectoryNotEmptyException(getString(path));
            updateDelete(inode);
        }
    }

    // Returns an out stream for either
    // (1) writing the contents of a new entry, if the entry exists, or
    // (2) updating/replacing the contents of the specified existing entry.
    private OutputStream getOutputStream(Entry e) throws IOException {
        if (e.mtime == -1)
            e.mtime = System.currentTimeMillis();
        if (e.method == -1)
            e.method = defaultCompressionMethod;
        // store size, compressed size, and crc-32 in datadescr
        e.flag = FLAG_DATADESCR;
        if (zc.isUTF8())
            e.flag |= FLAG_USE_UTF8;
        OutputStream os;
        if (useTempFile || e.size >= tempFileCreationThreshold) {
            e.file = getTempPathForEntry(null);
            os = Files.newOutputStream(e.file, WRITE);
        } else {
            os = new FileRolloverOutputStream(e);
        }
        if (e.method == METHOD_DEFLATED) {
            return new DeflatingEntryOutputStream(e, os);
        } else {
            return new EntryOutputStream(e, os);
        }
    }

    private class EntryOutputStream extends FilterOutputStream {
        private final Entry e;
        private long written;
        private boolean isClosed;

        EntryOutputStream(Entry e, OutputStream os) {
            super(os);
            this.e =  Objects.requireNonNull(e, "Zip entry is null");
            // this.written = 0;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            out.write(b);
            written += 1;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len)
                throws IOException {
            out.write(b, off, len);
            written += len;
        }

        @Override
        public synchronized void close() throws IOException {
            if (isClosed) {
                return;
            }
            isClosed = true;
            e.size = written;
            if (out instanceof FileRolloverOutputStream fros && fros.tmpFileOS == null) {
                e.bytes = fros.toByteArray();
            }
            super.close();
            update(e);
        }
    }

    // Output stream returned when writing "deflated" entries into memory,
    // to enable eager (possibly parallel) deflation and reduce memory required.
    private class DeflatingEntryOutputStream extends DeflaterOutputStream {
        private final CRC32 crc;
        private final Entry e;
        private boolean isClosed;

        DeflatingEntryOutputStream(Entry e, OutputStream os) {
            super(os, getDeflater());
            this.e = Objects.requireNonNull(e, "Zip entry is null");
            this.crc = new CRC32();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len)
                throws IOException {
            super.write(b, off, len);
            crc.update(b, off, len);
        }

        @Override
        public synchronized void close() throws IOException {
            if (isClosed)
                return;
            isClosed = true;
            finish();
            e.size  = def.getBytesRead();
            e.csize = def.getBytesWritten();
            e.crc = crc.getValue();
            if (out instanceof FileRolloverOutputStream fros && fros.tmpFileOS == null) {
                e.bytes = fros.toByteArray();
            }
            super.close();
            update(e);
            releaseDeflater(def);
        }
    }

    // Wrapper output stream class to write out a "stored" entry.
    // (1) this class does not close the underlying out stream when
    //     being closed.
    // (2) no need to be "synchronized", only used by sync()
    private class EntryOutputStreamCRC32 extends FilterOutputStream {
        private final CRC32 crc;
        private final Entry e;
        private long written;
        private boolean isClosed;

        EntryOutputStreamCRC32(Entry e, OutputStream os) {
            super(os);
            this.e =  Objects.requireNonNull(e, "Zip entry is null");
            this.crc = new CRC32();
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            crc.update(b);
            written += 1;
        }

        @Override
        public void write(byte[] b, int off, int len)
                throws IOException {
            out.write(b, off, len);
            crc.update(b, off, len);
            written += len;
        }

        @Override
        public void close() {
            if (isClosed)
                return;
            isClosed = true;
            e.size = e.csize = written;
            e.crc = crc.getValue();
        }
    }

    // Wrapper output stream class to write out a "deflated" entry.
    // (1) this class does not close the underlying out stream when
    //     being closed.
    // (2) no need to be "synchronized", only used by sync()
    private class EntryOutputStreamDef extends DeflaterOutputStream {
        private final CRC32 crc;
        private final Entry e;
        private boolean isClosed;

        EntryOutputStreamDef(Entry e, OutputStream os) {
            super(os, getDeflater());
            this.e = Objects.requireNonNull(e, "Zip entry is null");
            this.crc = new CRC32();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            crc.update(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (isClosed)
                return;
            isClosed = true;
            finish();
            e.size = def.getBytesRead();
            e.csize = def.getBytesWritten();
            e.crc = crc.getValue();
            releaseDeflater(def);
        }
    }

    // A wrapper around the ByteArrayOutputStream. This FileRolloverOutputStream
    // uses a threshold size to decide if the contents being written need to be
    // rolled over into a temporary file. Until the threshold is reached, writes
    // on this outputstream just write it to the internal in-memory byte array
    // held by the ByteArrayOutputStream. Once the threshold is reached, the
    // write operation on this outputstream first (and only once) creates a temporary file
    // and transfers the data that has so far been written in the internal
    // byte array, to that newly created file. The temp file is then opened
    // in append mode and any subsequent writes, including the one which triggered
    // the temporary file creation, will be written to the file.
    // Implementation note: the "write" and the "close" methods of this implementation
    // aren't "synchronized" because this FileRolloverOutputStream gets called
    // only from either DeflatingEntryOutputStream or EntryOutputStream, both of which
    // already have the necessary "synchronized" before calling these methods.
    private class FileRolloverOutputStream extends OutputStream {
        private ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        private final Entry entry;
        private OutputStream tmpFileOS;
        private long totalWritten = 0;

        private FileRolloverOutputStream(final Entry e) {
            this.entry = e;
        }

        @Override
        public void write(final int b) throws IOException {
            if (tmpFileOS != null) {
                // already rolled over, write to the file that has been created previously
                writeToFile(b);
                return;
            }
            if (totalWritten + 1 < tempFileCreationThreshold) {
                // write to our in-memory byte array
                baos.write(b);
                totalWritten++;
                return;
            }
            // rollover into a file
            transferToFile();
            writeToFile(b);
        }

        @Override
        public void write(final byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            if (tmpFileOS != null) {
                // already rolled over, write to the file that has been created previously
                writeToFile(b, off, len);
                return;
            }
            if (totalWritten + len < tempFileCreationThreshold) {
                // write to our in-memory byte array
                baos.write(b, off, len);
                totalWritten += len;
                return;
            }
            // rollover into a file
            transferToFile();
            writeToFile(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (tmpFileOS != null) {
                tmpFileOS.flush();
            }
        }

        @Override
        public void close() throws IOException {
            baos = null;
            if (tmpFileOS != null) {
                tmpFileOS.close();
            }
        }

        private void writeToFile(int b) throws IOException {
            tmpFileOS.write(b);
            totalWritten++;
        }

        private void writeToFile(byte[] b, int off, int len) throws IOException {
            tmpFileOS.write(b, off, len);
            totalWritten += len;
        }

        private void transferToFile() throws IOException {
            // create a tempfile
            entry.file = getTempPathForEntry(null);
            tmpFileOS = new BufferedOutputStream(Files.newOutputStream(entry.file));
            // transfer the already written data from the byte array buffer into this tempfile
            baos.writeTo(tmpFileOS);
            // release the underlying byte array
            baos = null;
        }

        private byte[] toByteArray() {
            return baos == null ? null : baos.toByteArray();
        }
    }

    private InputStream getInputStream(Entry e)
        throws IOException
    {
        InputStream eis;
        if (e.type == Entry.NEW) {
            if (e.bytes != null)
                eis = new ByteArrayInputStream(e.bytes);
            else if (e.file != null)
                eis = Files.newInputStream(e.file);
            else
                throw new ZipException("update entry data is missing");
        } else if (e.type == Entry.FILECH) {
            // FILECH result is un-compressed.
            eis = Files.newInputStream(e.file);
            // TBD: wrap to hook close()
            // streams.add(eis);
            return eis;
        } else {  // untouched CEN or COPY
            eis = new EntryInputStream(e);
        }
        if (e.method == METHOD_DEFLATED) {
            // MORE: Compute good size for inflater stream:
            long bufSize = e.size + 2; // Inflater likes a bit of slack
            if (bufSize > 65536)
                bufSize = 8192;
            final long size = e.size;
            eis = new InflaterInputStream(eis, getInflater(), (int)bufSize) {
                private boolean isClosed = false;
                public void close() throws IOException {
                    if (!isClosed) {
                        releaseInflater(inf);
                        this.in.close();
                        isClosed = true;
                        streams.remove(this);
                    }
                }
                // Override fill() method to provide an extra "dummy" byte
                // at the end of the input stream. This is required when
                // using the "nowrap" Inflater option. (it appears the new
                // zlib in 7 does not need it, but keep it for now)
                protected void fill() throws IOException {
                    if (eof) {
                        throw new EOFException(
                            "Unexpected end of ZLIB input stream");
                    }
                    len = this.in.read(buf, 0, buf.length);
                    if (len == -1) {
                        buf[0] = 0;
                        len = 1;
                        eof = true;
                    }
                    inf.setInput(buf, 0, len);
                }
                private boolean eof;

                public int available() {
                    if (isClosed)
                        return 0;
                    long avail = size - inf.getBytesWritten();
                    return avail > (long) Integer.MAX_VALUE ?
                        Integer.MAX_VALUE : (int) avail;
                }
            };
        } else if (e.method == METHOD_STORED) {
            // TBD: wrap/ it does not seem necessary
        } else {
            throw new ZipException("invalid compression method");
        }
        streams.add(eis);
        return eis;
    }

    // Inner class implementing the input stream used to read
    // a (possibly compressed) zip file entry.
    private class EntryInputStream extends InputStream {
        private long pos;                       // current position within entry data
        private long rem;                       // number of remaining bytes within entry

        EntryInputStream(Entry e)
            throws IOException
        {
            rem = e.csize;
            pos = e.locoff;
            if (pos == -1) {
                Entry e2 = getEntry(e.name);
                if (e2 == null) {
                    throw new ZipException("invalid loc for entry <" + getString(e.name) + ">");
                }
                pos = e2.locoff;
            }
            pos = -pos;  // lazy initialize the real data offset
        }

        public int read(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            initDataPos();
            if (rem == 0) {
                return -1;
            }
            if (len <= 0) {
                return 0;
            }
            if (len > rem) {
                len = (int) rem;
            }
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.position(off);
            bb.limit(off + len);
            long n = readAt(bb, pos);
            if (n > 0) {
                pos += n;
                rem -= n;
            }
            if (rem == 0) {
                close();
            }
            return (int)n;
        }

        public int read() throws IOException {
            byte[] b = new byte[1];
            if (read(b, 0, 1) == 1) {
                return b[0] & 0xff;
            } else {
                return -1;
            }
        }

        public long skip(long n) {
            ensureOpen();
            if (n > rem)
                n = rem;
            pos += n;
            rem -= n;
            if (rem == 0) {
                close();
            }
            return n;
        }

        public int available() {
            return rem > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rem;
        }

        public void close() {
            rem = 0;
            streams.remove(this);
        }

        private void initDataPos() throws IOException {
            if (pos <= 0) {
                pos = -pos + locpos;
                byte[] buf = new byte[LOCHDR];
                if (readNBytesAt(buf, 0, buf.length, pos) != LOCHDR) {
                    throw new ZipException("invalid LOC " + pos + " for entry reading");
                }
                if (LOCSIG(buf) != LOCSIG) {
                    throw new ZipException("invalid LOC header (bad signature)");
                }
                pos += LOCHDR + LOCNAM(buf) + LOCEXT(buf);
            }
        }
    }

    // Maxmum number of de/inflater we cache
    private final int MAX_FLATER = 20;
    // List of available Inflater objects for decompression
    private final List<Inflater> inflaters = new ArrayList<>();

    // Gets an inflater from the list of available inflaters or allocates
    // a new one.
    private Inflater getInflater() {
        synchronized (inflaters) {
            int size = inflaters.size();
            if (size > 0) {
                return inflaters.remove(size - 1);
            } else {
                return new Inflater(true);
            }
        }
    }

    // Releases the specified inflater to the list of available inflaters.
    private void releaseInflater(Inflater inf) {
        synchronized (inflaters) {
            if (inflaters.size() < MAX_FLATER) {
                inf.reset();
                inflaters.add(inf);
            } else {
                inf.end();
            }
        }
    }

    // List of available Deflater objects for compression
    private final List<Deflater> deflaters = new ArrayList<>();

    // Gets a deflater from the list of available deflaters or allocates
    // a new one.
    private Deflater getDeflater() {
        synchronized (deflaters) {
            int size = deflaters.size();
            if (size > 0) {
                return deflaters.remove(size - 1);
            } else {
                return new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            }
        }
    }

    // Releases the specified inflater to the list of available inflaters.
    private void releaseDeflater(Deflater def) {
        synchronized (deflaters) {
            if (deflaters.size() < MAX_FLATER) {
               def.reset();
               deflaters.add(def);
            } else {
               def.end();
            }
        }
    }

    // End of central directory record
    static class END {
        // The fields that are commented out below are not used by anyone and write() uses "0"
        // int  disknum;
        // int  sdisknum;
        // int  endsub;
        int  centot;        // 4 bytes
        long cenlen;        // 4 bytes
        long cenoff;        // 4 bytes
        // int  comlen;     // comment length
        // byte[] comment;

        // members of Zip64 end of central directory locator
        // int diskNum;
        long endpos;
        // int disktot;

        void write(OutputStream os, long offset, boolean forceEnd64) throws IOException {
            boolean hasZip64 = forceEnd64; // false;
            long xlen = cenlen;
            long xoff = cenoff;
            if (xlen >= ZIP64_MINVAL) {
                xlen = ZIP64_MINVAL;
                hasZip64 = true;
            }
            if (xoff >= ZIP64_MINVAL) {
                xoff = ZIP64_MINVAL;
                hasZip64 = true;
            }
            int count = centot;
            if (count >= ZIP64_MINVAL32) {
                count = ZIP64_MINVAL32;
                hasZip64 = true;
            }
            if (hasZip64) {
                //zip64 end of central directory record
                writeInt(os, ZIP64_ENDSIG);       // zip64 END record signature
                writeLong(os, ZIP64_ENDHDR - 12); // size of zip64 end
                writeShort(os, 45);               // version made by
                writeShort(os, 45);               // version needed to extract
                writeInt(os, 0);                  // number of this disk
                writeInt(os, 0);                  // central directory start disk
                writeLong(os, centot);            // number of directory entries on disk
                writeLong(os, centot);            // number of directory entries
                writeLong(os, cenlen);            // length of central directory
                writeLong(os, cenoff);            // offset of central directory

                //zip64 end of central directory locator
                writeInt(os, ZIP64_LOCSIG);       // zip64 END locator signature
                writeInt(os, 0);                  // zip64 END start disk
                writeLong(os, offset);            // offset of zip64 END
                writeInt(os, 1);                  // total number of disks (?)
            }
            writeInt(os, ENDSIG);                 // END record signature
            writeShort(os, 0);                    // number of this disk
            writeShort(os, 0);                    // central directory start disk
            writeShort(os, count);                // number of directory entries on disk
            writeShort(os, count);                // total number of directory entries
            writeInt(os, xlen);                   // length of central directory
            writeInt(os, xoff);                   // offset of central directory
            writeShort(os, 0);                    // zip file comment, not used
        }
    }

    // Internal node that links a "name" to its pos in cen table.
    // The node itself can be used as a "key" to lookup itself in
    // the HashMap inodes.
    static class IndexNode {
        byte[]  name;
        int     hashcode;    // node is hashable/hashed by its name
        boolean isdir;
        int     pos = -1;    // position in cen table, -1 means the
                             // entry does not exist in zip file
        IndexNode child;     // first child
        IndexNode sibling;   // next sibling

        IndexNode() {}

        IndexNode(byte[] name, boolean isdir) {
            name(name);
            this.isdir = isdir;
            this.pos = -1;
        }

        IndexNode(byte[] name, int pos) {
            name(name);
            this.pos = pos;
        }

        // constructor for initCEN() (1) remove trailing '/' (2) pad leading '/'
        IndexNode(byte[] cen, int pos, int nlen) {
            int noff = pos + CENHDR;
            if (cen[noff + nlen - 1] == '/') {
                isdir = true;
                nlen--;
            }
            if (nlen > 0 && cen[noff] == '/') {
                name = Arrays.copyOfRange(cen, noff, noff + nlen);
            } else {
                name = new byte[nlen + 1];
                System.arraycopy(cen, noff, name, 1, nlen);
                name[0] = '/';
            }
            name(normalize(name));
            this.pos = pos;
        }

        // Normalize the IndexNode.name field.
        private byte[] normalize(byte[] path) {
            int len = path.length;
            if (len == 0)
                return path;
            byte prevC = 0;
            for (int pathPos = 0; pathPos < len; pathPos++) {
                byte c = path[pathPos];
                if (c == '/' && prevC == '/')
                    return normalize(path, pathPos - 1);
                prevC = c;
            }
            if (len > 1 && prevC == '/') {
                return Arrays.copyOf(path, len - 1);
            }
            return path;
        }

        private byte[] normalize(byte[] path, int off) {
            // As we know we have at least one / to trim, we can reduce
            // the size of the resulting array
            byte[] to = new byte[path.length - 1];
            int pathPos = 0;
            while (pathPos < off) {
                to[pathPos] = path[pathPos];
                pathPos++;
            }
            int toPos = pathPos;
            byte prevC = 0;
            while (pathPos < path.length) {
                byte c = path[pathPos++];
                if (c == '/' && prevC == '/')
                    continue;
                to[toPos++] = c;
                prevC = c;
            }
            if (toPos > 1 && to[toPos - 1] == '/')
                toPos--;
            return (toPos == to.length) ? to : Arrays.copyOf(to, toPos);
        }

        private static final ThreadLocal<IndexNode> cachedKey = new ThreadLocal<>();

        static final IndexNode keyOf(byte[] name) { // get a lookup key;
            IndexNode key = cachedKey.get();
            if (key == null) {
                key = new IndexNode(name, -1);
                cachedKey.set(key);
            }
            return key.as(name);
        }

        final void name(byte[] name) {
            this.name = name;
            this.hashcode = Arrays.hashCode(name);
        }

        final IndexNode as(byte[] name) {           // reuse the node, mostly
            name(name);                             // as a lookup "key"
            return this;
        }

        boolean isDir() {
            return isdir;
        }

        /**
         * Check name if it contains a "." or ".." path element
         * @return true if the path contains a "." or ".." entry; false otherwise
         */
        private boolean pathHasDotOrDotDot() {
            // name always includes "/" in path[0]
            assert name[0] == '/';
            if (name.length == 1) {
                return false;
            }
            int index = 1;
            while (index < name.length) {
                int start = index;
                while (index < name.length && name[index] != '/') {
                    index++;
                }
                if (name[start] == '.') {
                    int len = index - start;
                    if (len == 1 || (name[start + 1] == '.' && len == 2)) {
                        return true;
                    }
                }
                index++;
            }
            return false;
        }

        protected String nameAsString() {
            return new String(name);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof IndexNode)) {
                return false;
            }
            if (other instanceof ParentLookup) {
                return ((ParentLookup)other).equals(this);
            }
            return Arrays.equals(name, ((IndexNode)other).name);
        }

        @Override
        public int hashCode() {
            return hashcode;
        }

        @Override
        public String toString() {
            return nameAsString() + (isdir ? " (dir)" : " ") + ", index: " + pos;
        }
    }

    static class Entry extends IndexNode implements ZipFileAttributes {
        static final int CEN    = 1;  // entry read from cen
        static final int NEW    = 2;  // updated contents in bytes or file
        static final int FILECH = 3;  // fch update in "file"
        static final int COPY   = 4;  // copy of a CEN entry

        byte[] bytes;                 // updated content bytes
        Path   file;                  // use tmp file to store bytes;
        int    type = CEN;            // default is the entry read from cen

        // entry attributes
        int    version;
        int    flag;
        int    externalFileAttributes = -1; // file type, setuid, setgid, sticky, posix permissions
        int    method = -1;    // compression method
        long   mtime  = -1;    // last modification time (in DOS time)
        long   atime  = -1;    // last access time
        long   ctime  = -1;    // create time
        long   crc    = -1;    // crc-32 of entry data
        long   csize  = -1;    // compressed size of entry data
        long   size   = -1;    // uncompressed size of entry data
        byte[] extra;

        // CEN
        // The fields that are commented out below are not used by anyone and write() uses "0"
        // int    versionMade;
        // int    disk;
        // int    attrs;
        // long   attrsEx;
        long   locoff;
        byte[] comment;

        Entry(byte[] name, boolean isdir, int method) {
            name(name);
            this.isdir = isdir;
            this.mtime  = this.ctime = this.atime = System.currentTimeMillis();
            this.crc    = 0;
            this.size   = 0;
            this.csize  = 0;
            this.method = method;
        }

        @SuppressWarnings("unchecked")
        Entry(byte[] name, int type, boolean isdir, int method, FileAttribute<?>... attrs) {
            this(name, isdir, method);
            this.type = type;
            for (FileAttribute<?> attr : attrs) {
                String attrName = attr.name();
                if (attrName.equals("posix:permissions")) {
                    externalFileAttributes = ZipUtils.permsToFlags((Set<PosixFilePermission>)attr.value());
                }
            }
        }

        Entry(byte[] name, Path file, int type, FileAttribute<?>... attrs) {
            this(name, type, false, METHOD_STORED, attrs);
            this.file = file;
        }

        Entry(Entry e, int type, int compressionMethod) {
            this(e, type);
            this.method = compressionMethod;
        }

        Entry(Entry e, int type) {
            name(e.name);
            this.isdir     = e.isdir;
            this.version   = e.version;
            this.ctime     = e.ctime;
            this.atime     = e.atime;
            this.mtime     = e.mtime;
            this.crc       = e.crc;
            this.size      = e.size;
            this.csize     = e.csize;
            this.method    = e.method;
            this.extra     = e.extra;
            /*
            this.versionMade = e.versionMade;
            this.disk      = e.disk;
            this.attrs     = e.attrs;
            this.attrsEx   = e.attrsEx;
            */
            this.locoff    = e.locoff;
            this.comment   = e.comment;
            this.externalFileAttributes = e.externalFileAttributes;
            this.type      = type;
        }

        Entry(ZipFileSystem zipfs, IndexNode inode) throws IOException {
            readCEN(zipfs, inode);
        }

        // Calculates a suitable base for the version number to
        // be used for fields version made by/version needed to extract.
        // The lower bytes of these 2 byte fields hold the version number
        // (value/10 = major; value%10 = minor)
        // For different features certain minimum versions apply:
        // stored = 10 (1.0), deflated = 20 (2.0), zip64 = 45 (4.5)
        private int version(boolean zip64) throws ZipException {
            if (zip64) {
                return 45;
            }
            if (method == METHOD_DEFLATED)
                return 20;
            else if (method == METHOD_STORED)
                return 10;
            throw new ZipException("unsupported compression method");
        }

        /**
         * Adds information about compatibility of file attribute information
         * to a version value.
         */
        private int versionMadeBy(int version) {
            return (externalFileAttributes < 0) ? version :
                VERSION_MADE_BY_BASE_UNIX | (version & 0xff);
        }

        ///////////////////// CEN //////////////////////
        private void readCEN(ZipFileSystem zipfs, IndexNode inode) throws IOException {
            byte[] cen = zipfs.cen;
            int pos = inode.pos;
            if (!cenSigAt(cen, pos))
                throw new ZipException("invalid CEN header (bad signature)");
            version     = CENVER(cen, pos);
            flag        = CENFLG(cen, pos);
            method      = CENHOW(cen, pos);
            mtime       = dosToJavaTime(CENTIM(cen, pos));
            crc         = CENCRC(cen, pos);
            csize       = CENSIZ(cen, pos);
            size        = CENLEN(cen, pos);
            int nlen    = CENNAM(cen, pos);
            int elen    = CENEXT(cen, pos);
            int clen    = CENCOM(cen, pos);
            int diskNo  = CENDSK(cen, pos);
            /*
            versionMade = CENVEM(cen, pos);
            attrs       = CENATT(cen, pos);
            attrsEx     = CENATX(cen, pos);
            */
            if (CENVEM_FA(cen, pos) == FILE_ATTRIBUTES_UNIX) {
                externalFileAttributes = (CENATX_PERMS(cen, pos) & 0xFFFF); // 16 bits for file type, setuid, setgid, sticky + perms
            }
            locoff      = CENOFF(cen, pos);
            pos += CENHDR;
            this.name = inode.name;
            this.isdir = inode.isdir;
            this.hashcode = inode.hashcode;

            pos += nlen;
            if (elen > 0) {
                extra = Arrays.copyOfRange(cen, pos, pos + elen);
                pos += elen;
                readExtra(zipfs);
            } else if (elen == 0 && (size == ZIP64_MINVAL || csize == ZIP64_MINVAL
                    || locoff == ZIP64_MINVAL || diskNo == ZIP64_MINVAL32)) {
                throw new ZipException("Invalid CEN header (invalid zip64 extra len size)");
            }
            if (clen > 0) {
                comment = Arrays.copyOfRange(cen, pos, pos + clen);
            }
        }

        private int writeCEN(OutputStream os) throws IOException {
            long csize0  = csize;
            long size0   = size;
            long locoff0 = locoff;
            int elen64   = 0;                // extra for ZIP64
            int elenNTFS = 0;                // extra for NTFS (a/c/mtime)
            int elenEXTT = 0;                // extra for Extended Timestamp
            boolean foundExtraTime = false;  // if time stamp NTFS, EXTT present

            byte[] zname = isdir ? toDirectoryPath(name) : name;

            // confirm size/length
            int nlen = (zname != null) ? zname.length - 1 : 0;  // name has [0] as "slash"
            int elen = (extra != null) ? extra.length : 0;
            int eoff = 0;
            int clen = (comment != null) ? comment.length : 0;
            if (csize >= ZIP64_MINVAL) {
                csize0 = ZIP64_MINVAL;
                elen64 += 8;                 // csize(8)
            }
            if (size >= ZIP64_MINVAL) {
                size0 = ZIP64_MINVAL;        // size(8)
                elen64 += 8;
            }
            if (locoff >= ZIP64_MINVAL) {
                locoff0 = ZIP64_MINVAL;
                elen64 += 8;                 // offset(8)
            }
            if (elen64 != 0) {
                elen64 += 4;                 // header and data sz 4 bytes
            }
            boolean zip64 = (elen64 != 0);
            int version0 = version(zip64);
            while (eoff + 4 < elen) {
                int tag = SH(extra, eoff);
                int sz = SH(extra, eoff + 2);
                if (tag == EXTID_EXTT || tag == EXTID_NTFS) {
                    foundExtraTime = true;
                }
                eoff += (4 + sz);
            }
            if (!foundExtraTime) {
                if (isWindows) {             // use NTFS
                    elenNTFS = 36;           // total 36 bytes
                } else {                     // Extended Timestamp otherwise
                    elenEXTT = 9;            // only mtime in cen
                }
            }
            writeInt(os, CENSIG);            // CEN header signature
            writeShort(os, versionMadeBy(version0)); // version made by
            writeShort(os, version0);        // version needed to extract
            writeShort(os, flag);            // general purpose bit flag
            writeShort(os, method);          // compression method
                                             // last modification time
            writeInt(os, (int)javaToDosTime(mtime));
            writeInt(os, crc);               // crc-32
            writeInt(os, csize0);            // compressed size
            writeInt(os, size0);             // uncompressed size
            writeShort(os, nlen);
            writeShort(os, elen + elen64 + elenNTFS + elenEXTT);

            if (comment != null) {
                writeShort(os, Math.min(clen, 0xffff));
            } else {
                writeShort(os, 0);
            }
            writeShort(os, 0);              // starting disk number
            writeShort(os, 0);              // internal file attributes (unused)
            writeInt(os, externalFileAttributes > 0 ? externalFileAttributes << 16 : 0); // external file
                                            // attributes, used for storing posix
                                            // permissions
            writeInt(os, locoff0);          // relative offset of local header
            writeBytes(os, zname, 1, nlen);
            if (zip64) {
                writeShort(os, EXTID_ZIP64);// Zip64 extra
                writeShort(os, elen64 - 4); // size of "this" extra block
                if (size0 == ZIP64_MINVAL)
                    writeLong(os, size);
                if (csize0 == ZIP64_MINVAL)
                    writeLong(os, csize);
                if (locoff0 == ZIP64_MINVAL)
                    writeLong(os, locoff);
            }
            if (elenNTFS != 0) {
                writeShort(os, EXTID_NTFS);
                writeShort(os, elenNTFS - 4);
                writeInt(os, 0);            // reserved
                writeShort(os, 0x0001);     // NTFS attr tag
                writeShort(os, 24);
                writeLong(os, javaToWinTime(mtime));
                writeLong(os, javaToWinTime(atime));
                writeLong(os, javaToWinTime(ctime));
            }
            if (elenEXTT != 0) {
                writeShort(os, EXTID_EXTT);
                writeShort(os, elenEXTT - 4);
                if (ctime == -1)
                    os.write(0x3);          // mtime and atime
                else
                    os.write(0x7);          // mtime, atime and ctime
                writeInt(os, javaToUnixTime(mtime));
            }
            if (extra != null)              // whatever not recognized
                writeBytes(os, extra);
            if (comment != null)            //TBD: 0, Math.min(commentBytes.length, 0xffff));
                writeBytes(os, comment);
            return CENHDR + nlen + elen + clen + elen64 + elenNTFS + elenEXTT;
        }

        ///////////////////// LOC //////////////////////

        private int writeLOC(OutputStream os) throws IOException {
            byte[] zname = isdir ? toDirectoryPath(name) : name;
            int nlen = (zname != null) ? zname.length - 1 : 0; // [0] is slash
            int elen = (extra != null) ? extra.length : 0;
            boolean foundExtraTime = false;     // if extra timestamp present
            int eoff = 0;
            int elen64 = 0;
            boolean zip64 = false;
            int elenEXTT = 0;
            int elenNTFS = 0;
            writeInt(os, LOCSIG);               // LOC header signature
            if ((flag & FLAG_DATADESCR) != 0) {
                writeShort(os, version(false)); // version needed to extract
                writeShort(os, flag);           // general purpose bit flag
                writeShort(os, method);         // compression method
                // last modification time
                writeInt(os, (int)javaToDosTime(mtime));
                // store size, uncompressed size, and crc-32 in data descriptor
                // immediately following compressed entry data
                writeInt(os, 0);
                writeInt(os, 0);
                writeInt(os, 0);
            } else {
                if (csize >= ZIP64_MINVAL || size >= ZIP64_MINVAL) {
                    elen64 = 20;    //headid(2) + size(2) + size(8) + csize(8)
                    zip64 = true;
                }
                writeShort(os, version(zip64)); // version needed to extract
                writeShort(os, flag);           // general purpose bit flag
                writeShort(os, method);         // compression method
                                                // last modification time
                writeInt(os, (int)javaToDosTime(mtime));
                writeInt(os, crc);              // crc-32
                if (zip64) {
                    writeInt(os, ZIP64_MINVAL);
                    writeInt(os, ZIP64_MINVAL);
                } else {
                    writeInt(os, csize);        // compressed size
                    writeInt(os, size);         // uncompressed size
                }
            }
            while (eoff + 4 < elen) {
                int tag = SH(extra, eoff);
                int sz = SH(extra, eoff + 2);
                if (tag == EXTID_EXTT || tag == EXTID_NTFS) {
                    foundExtraTime = true;
                }
                eoff += (4 + sz);
            }
            if (!foundExtraTime) {
                if (isWindows) {
                    elenNTFS = 36;              // NTFS, total 36 bytes
                } else {                        // on unix use "ext time"
                    elenEXTT = 9;
                    if (atime != -1)
                        elenEXTT += 4;
                    if (ctime != -1)
                        elenEXTT += 4;
                }
            }
            writeShort(os, nlen);
            writeShort(os, elen + elen64 + elenNTFS + elenEXTT);
            writeBytes(os, zname, 1, nlen);
            if (zip64) {
                writeShort(os, EXTID_ZIP64);
                writeShort(os, 16);
                writeLong(os, size);
                writeLong(os, csize);
            }
            if (elenNTFS != 0) {
                writeShort(os, EXTID_NTFS);
                writeShort(os, elenNTFS - 4);
                writeInt(os, 0);            // reserved
                writeShort(os, 0x0001);     // NTFS attr tag
                writeShort(os, 24);
                writeLong(os, javaToWinTime(mtime));
                writeLong(os, javaToWinTime(atime));
                writeLong(os, javaToWinTime(ctime));
            }
            if (elenEXTT != 0) {
                writeShort(os, EXTID_EXTT);
                writeShort(os, elenEXTT - 4);// size for the following data block
                int fbyte = 0x1;
                if (atime != -1)           // mtime and atime
                    fbyte |= 0x2;
                if (ctime != -1)           // mtime, atime and ctime
                    fbyte |= 0x4;
                os.write(fbyte);           // flags byte
                writeInt(os, javaToUnixTime(mtime));
                if (atime != -1)
                    writeInt(os, javaToUnixTime(atime));
                if (ctime != -1)
                    writeInt(os, javaToUnixTime(ctime));
            }
            if (extra != null) {
                writeBytes(os, extra);
            }
            return LOCHDR + nlen + elen + elen64 + elenNTFS + elenEXTT;
        }

        // Data Descriptor
        private int writeEXT(OutputStream os) throws IOException {
            writeInt(os, EXTSIG);           // EXT header signature
            writeInt(os, crc);              // crc-32
            if (csize >= ZIP64_MINVAL || size >= ZIP64_MINVAL) {
                writeLong(os, csize);
                writeLong(os, size);
                return 24;
            } else {
                writeInt(os, csize);        // compressed size
                writeInt(os, size);         // uncompressed size
                return 16;
            }
        }

        // read NTFS, UNIX and ZIP64 data from cen.extra
        private void readExtra(ZipFileSystem zipfs) throws IOException {
            // Note that Section 4.5, Extensible data fields, of the PKWARE ZIP File
            // Format Specification does not mandate a specific order for the
            // data in the extra field, therefore Zip FS cannot assume the data
            // is written in the same order by Zip libraries as Zip FS.
            if (extra == null)
                return;
            int elen = extra.length;
            // Extra field Length cannot exceed 65,535 bytes per the PKWare
            // APP.note 4.4.11
            if (elen > 0xFFFF) {
                throw new ZipException("invalid extra field length");
            }
            int off = 0;
            int newOff = 0;
            boolean hasZip64LocOffset = false;
            while (off + 4 <= elen) {
                // extra spec: HeaderID+DataSize+Data
                int pos = off;
                int tag = SH(extra, pos);
                int sz = SH(extra, pos + 2);
                pos += 4;
                if (pos + sz > elen) {        // invalid data
                    throw new ZipException(String.format(
                            "Invalid CEN header (invalid extra data field size for " +
                                    "tag: 0x%04x size: %d)",
                            tag, sz));
                }
                switch (tag) {
                case EXTID_ZIP64:
                    if (size == ZIP64_MINVAL) {
                        if (pos + 8 > elen)  // invalid zip64 extra
                            break;           // fields, just skip
                        size = LL(extra, pos);
                        if (size < 0) {
                            throw new ZipException("Invalid zip64 extra block size value");
                        }
                        pos += 8;
                    }
                    if (csize == ZIP64_MINVAL) {
                        if (pos + 8 > elen)
                            break;
                        csize = LL(extra, pos);
                        if (csize < 0) {
                            throw new ZipException("Invalid zip64 extra block compressed size value");
                        }
                        pos += 8;
                    }
                    if (locoff == ZIP64_MINVAL) {
                        if (pos + 8 > elen)
                            break;
                        locoff = LL(extra, pos);
                        if (locoff < 0) {
                            throw new ZipException("Invalid zip64 extra block LOC offset value");
                        }
                    }
                    break;
                case EXTID_NTFS:
                    if (sz < 32)
                        break;
                    pos += 4;    // reserved 4 bytes
                    if (SH(extra, pos) !=  0x0001)
                        break;
                    if (SH(extra, pos + 2) != 24)
                        break;
                    // override the loc field, datatime here is
                    // more "accurate"
                    mtime  = winToJavaTime(LL(extra, pos + 4));
                    atime  = winToJavaTime(LL(extra, pos + 12));
                    ctime  = winToJavaTime(LL(extra, pos + 20));
                    break;
                case EXTID_EXTT:
                    // spec says the Extended timestamp in cen only has mtime
                    // need to read the loc to get the extra a/ctime, if flag
                    // "zipinfo-time" is not specified to false;
                    // there is performance cost (move up to loc and read) to
                    // access the loc table foreach entry;
                    if (zipfs.noExtt) {
                        if (sz == 5)
                            mtime = unixToJavaTime(LG(extra, pos + 1));
                         break;
                    }
                    // If the LOC offset is 0xFFFFFFFF, then we need to read the
                    // LOC offset from the EXTID_ZIP64 extra data. Therefore,
                    // wait until all the CEN extra data fields have been processed
                    // prior to reading the LOC extra data field in order to obtain
                    // the Info-ZIP Extended Timestamp.
                    if (locoff != ZIP64_MINVAL) {
                        readLocEXTT(zipfs);
                    } else {
                        hasZip64LocOffset = true;
                    }
                    break;
                default:    // unknown tag
                    System.arraycopy(extra, off, extra, newOff, sz + 4);
                    newOff += (sz + 4);
                }
                off += (sz + 4);
            }

            // We need to read the LOC extra data and the LOC offset was obtained
            // from the EXTID_ZIP64 field.
            if (hasZip64LocOffset) {
                readLocEXTT(zipfs);
            }

            if (newOff != 0 && newOff != extra.length)
                extra = Arrays.copyOf(extra, newOff);
            else
                extra = null;
        }

        /**
         * Read the LOC extra field to obtain the Info-ZIP Extended Timestamp fields
         * @param zipfs The Zip FS to use
         * @throws IOException If an error occurs
         */
        private void readLocEXTT(ZipFileSystem zipfs) throws IOException {
            byte[] buf = new byte[LOCHDR];
            if (zipfs.readNBytesAt(buf, 0, buf.length , locoff)
                != buf.length)
                throw new ZipException("loc: reading failed");
            if (!locSigAt(buf, 0))
                throw new ZipException("R"
                                   + Long.toString(getSig(buf, 0), 16));
            int locElen = LOCEXT(buf);
            if (locElen < 9)    // EXTT is at least 9 bytes
                return;
            int locNlen = LOCNAM(buf);
            buf = new byte[locElen];
            if (zipfs.readNBytesAt(buf, 0, buf.length , locoff + LOCHDR + locNlen)
                != buf.length)
                throw new ZipException("loc extra: reading failed");
            int locPos = 0;
            while (locPos + 4 < buf.length) {
                int locTag = SH(buf, locPos);
                int locSZ  = SH(buf, locPos + 2);
                locPos += 4;
                if (locTag  != EXTID_EXTT) {
                    locPos += locSZ;
                     continue;
                }
                int end = locPos + locSZ - 4;
                int flag = CH(buf, locPos++);
                if ((flag & 0x1) != 0 && locPos <= end) {
                    mtime = unixToJavaTime(LG(buf, locPos));
                    locPos += 4;
                }
                if ((flag & 0x2) != 0 && locPos <= end) {
                    atime = unixToJavaTime(LG(buf, locPos));
                    locPos += 4;
                }
                if ((flag & 0x4) != 0 && locPos <= end) {
                    ctime = unixToJavaTime(LG(buf, locPos));
                }
                break;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(1024);
            Formatter fm = new Formatter(sb);
            fm.format("    name            : %s%n", nameAsString());
            fm.format("    creationTime    : %tc%n", creationTime().toMillis());
            fm.format("    lastAccessTime  : %tc%n", lastAccessTime().toMillis());
            fm.format("    lastModifiedTime: %tc%n", lastModifiedTime().toMillis());
            fm.format("    isRegularFile   : %b%n", isRegularFile());
            fm.format("    isDirectory     : %b%n", isDirectory());
            fm.format("    isSymbolicLink  : %b%n", isSymbolicLink());
            fm.format("    isOther         : %b%n", isOther());
            fm.format("    fileKey         : %s%n", fileKey());
            fm.format("    size            : %d%n", size());
            fm.format("    compressedSize  : %d%n", compressedSize());
            fm.format("    crc             : %x%n", crc());
            fm.format("    method          : %d%n", method());
            Set<PosixFilePermission> permissions = storedPermissions().orElse(null);
            if (permissions != null) {
                fm.format("    permissions     : %s%n", permissions);
            }
            fm.close();
            return sb.toString();
        }

        ///////// basic file attributes ///////////
        @Override
        public FileTime creationTime() {
            return FileTime.fromMillis(ctime == -1 ? mtime : ctime);
        }

        @Override
        public boolean isDirectory() {
            return isDir();
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public boolean isRegularFile() {
            return !isDir();
        }

        @Override
        public FileTime lastAccessTime() {
            return FileTime.fromMillis(atime == -1 ? mtime : atime);
        }

        @Override
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(mtime);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public Object fileKey() {
            return null;
        }

        ///////// zip file attributes ///////////

        @Override
        public long compressedSize() {
            return csize;
        }

        @Override
        public long crc() {
            return crc;
        }

        @Override
        public int method() {
            return method;
        }

        @Override
        public byte[] extra() {
            if (extra != null)
                return Arrays.copyOf(extra, extra.length);
            return null;
        }

        @Override
        public byte[] comment() {
            if (comment != null)
                return Arrays.copyOf(comment, comment.length);
            return null;
        }

        @Override
        public Optional<Set<PosixFilePermission>> storedPermissions() {
            Set<PosixFilePermission> perms = null;
            if (externalFileAttributes != -1) {
                perms = HashSet.newHashSet(PosixFilePermission.values().length);
                for (PosixFilePermission perm : PosixFilePermission.values()) {
                    if ((externalFileAttributes & ZipUtils.permToFlag(perm)) != 0) {
                        perms.add(perm);
                    }
                }
            }
            return Optional.ofNullable(perms);
        }
    }

    final class PosixEntry extends Entry implements PosixFileAttributes {
        private UserPrincipal owner = defaultOwner;
        private GroupPrincipal group = defaultGroup;

        PosixEntry(byte[] name, boolean isdir, int method) {
            super(name, isdir, method);
        }

        PosixEntry(byte[] name, int type, boolean isdir, int method, FileAttribute<?>... attrs) {
            super(name, type, isdir, method, attrs);
        }

        PosixEntry(byte[] name, Path file, int type, FileAttribute<?>... attrs) {
            super(name, file, type, attrs);
        }

        PosixEntry(PosixEntry e, int type, int compressionMethod) {
            super(e, type);
            this.method = compressionMethod;
        }

        PosixEntry(PosixEntry e, int type) {
            super(e, type);
            this.owner = e.owner;
            this.group = e.group;
        }

        PosixEntry(ZipFileSystem zipfs, IndexNode inode) throws IOException {
            super(zipfs, inode);
        }

        @Override
        public UserPrincipal owner() {
            return owner;
        }

        @Override
        public GroupPrincipal group() {
            return group;
        }

        @Override
        public Set<PosixFilePermission> permissions() {
            // supportPosix ==> (defaultPermissions != null)
            return storedPermissions().orElse(defaultPermissions);
        }
    }

    // purely for parent lookup, so we don't have to copy the parent
    // name every time
    static class ParentLookup extends IndexNode {
        int len;
        ParentLookup() {}

        final ParentLookup as(byte[] name, int len) { // as a lookup "key"
            name(name, len);
            return this;
        }

        void name(byte[] name, int len) {
            this.name = name;
            this.len = len;
            // calculate the hashcode the same way as Arrays.hashCode() does
            int result = 1;
            for (int i = 0; i < len; i++)
                result = 31 * result + name[i];
            this.hashcode = result;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof IndexNode)) {
                return false;
            }
            byte[] oname = ((IndexNode)other).name;
            return Arrays.equals(name, 0, len,
                                 oname, 0, oname.length);
        }
    }
}
