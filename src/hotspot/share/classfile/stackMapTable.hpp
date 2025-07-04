/*
 * Copyright (c) 2003, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 *
 */

#ifndef SHARE_CLASSFILE_STACKMAPTABLE_HPP
#define SHARE_CLASSFILE_STACKMAPTABLE_HPP

#include "classfile/stackMapFrame.hpp"
#include "classfile/verifier.hpp"
#include "memory/allocation.hpp"
#include "oops/constantPool.hpp"
#include "oops/method.hpp"
#include "utilities/bytes.hpp"
#include "utilities/globalDefinitions.hpp"

class StackMapReader;

// StackMapTable class is the StackMap table used by type checker
class StackMapTable : public StackObj {
 private:
  // Logically, the _frame_count (as well as many fields in the StackFrame)
  // should be a u2, but if we defined the variable as that type it will
  // be difficult to detect/recover from overflow or underflow conditions.
  // Widening the type and making it signed will help detect these.
  int32_t              _code_length;
  int32_t              _frame_count;     // Stackmap frame count
  GrowableArray<StackMapFrame*>* _frame_array;

 public:
  StackMapTable(StackMapReader* reader, TRAPS);

  inline int32_t get_frame_count() const { return _frame_count; }
  inline int get_offset(int index) const {
    return _frame_array->at(index)->offset();
  }

  // Match and/or update current_frame to the frame in stackmap table with
  // specified offset. Return true if the two frames match.
  bool match_stackmap(
    StackMapFrame* current_frame, int32_t offset,
    bool match, bool update, ErrorContext* ctx, TRAPS) const;
  // Match and/or update current_frame to the frame in stackmap table with
  // specified offset and frame index. Return true if the two frames match.
  bool match_stackmap(
    StackMapFrame* current_frame, int32_t offset, int32_t frame_index,
    bool match, bool update, ErrorContext* ctx, TRAPS) const;

  // Check jump instructions. Make sure there are no uninitialized
  // instances on backward branch.
  void check_jump_target(StackMapFrame* frame, int32_t target, TRAPS) const;

  // The following methods are only used inside this class.

  // Returns the frame array index where the frame with offset is stored.
  int get_index_from_offset(int32_t offset) const;

  void print_on(outputStream* str) const;
};

class StackMapStream : StackObj {
 private:
  Array<u1>* _data;
  int _index;
 public:
  StackMapStream(Array<u1>* ah)
    : _data(ah), _index(0) {
  }
  u1 get_u1(TRAPS) {
    if (_data == nullptr || _index >= _data->length()) {
      stackmap_format_error("access beyond the end of attribute", CHECK_0);
    }
    return _data->at(_index++);
  }
  u2 get_u2(TRAPS) {
    if (_data == nullptr || _index >= _data->length() - 1) {
      stackmap_format_error("access beyond the end of attribute", CHECK_0);
    }
    u2 res = Bytes::get_Java_u2(_data->adr_at(_index));
    _index += 2;
    return res;
  }
  bool at_end() {
    return (_data == nullptr) || (_index == _data->length());
  }
  static void stackmap_format_error(const char* msg, TRAPS);
};

class StackMapReader : StackObj {
  friend class VM_RedefineClasses;
 private:
  // information about the class and method
  constantPoolHandle  _cp;
  ClassVerifier* _verifier;
  StackMapStream* _stream;
  char* _code_data;
  int32_t _code_length;

  // information from the attribute
  int32_t  _frame_count;

  // Number of frames parsed
  int32_t  _parsed_frame_count;

  // Previous frame buffer
  StackMapFrame* _prev_frame;

  // information from method
  u2 _max_locals;
  u2 _max_stack;

  // Check if reading first entry
  bool _first;

  StackMapFrame* next_helper(TRAPS);
  void check_offset(StackMapFrame* frame);
  void check_size(TRAPS);
  int32_t chop(VerificationType* locals, int32_t length, int32_t chops);
  VerificationType parse_verification_type(u1* flags, TRAPS);
  void check_verification_type_array_size(
      int32_t size, int32_t max_size, TRAPS) {
    if (size < 0 || size > max_size) {
      // Since this error could be caused someone rewriting the method
      // but not knowing to update the stackmap data, we call the
      // verifier's error method, which may not throw an exception and
      // failover to the old verifier instead.
      _verifier->class_format_error(
        "StackMapTable format error: bad type array size");
    }
  }

  enum {
    SAME_FRAME_START = 0,
    SAME_FRAME_END = 63,
    SAME_LOCALS_1_STACK_ITEM_FRAME_START = 64,
    SAME_LOCALS_1_STACK_ITEM_FRAME_END = 127,
    RESERVED_START = 128,
    RESERVED_END = 246,
    SAME_LOCALS_1_STACK_ITEM_EXTENDED = 247,
    CHOP_FRAME_START = 248,
    CHOP_FRAME_END = 250,
    SAME_FRAME_EXTENDED = 251,
    APPEND_FRAME_START = 252,
    APPEND_FRAME_END = 254,
    FULL_FRAME = 255
  };

 public:
  // Constructor
  StackMapReader(ClassVerifier* v, StackMapStream* stream,
                 char* code_data, int32_t code_len,
                 StackMapFrame* init_frame,
                 u2 max_locals, u2 max_stack, TRAPS);

  inline int32_t get_frame_count()   const { return _frame_count; }
  inline StackMapFrame* prev_frame() const { return _prev_frame; }
  inline char* code_data()           const { return _code_data; }
  inline int32_t code_length()       const { return _code_length; }
  inline bool at_end()               const { return _stream->at_end(); }

  StackMapFrame* next(TRAPS);
  void check_end(TRAPS);
};

#endif // SHARE_CLASSFILE_STACKMAPTABLE_HPP
