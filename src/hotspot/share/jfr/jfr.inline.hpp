/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_JFR_JFR_INLINE_HPP
#define SHARE_JFR_JFR_INLINE_HPP

#include "jfr/jfr.hpp"

#include "jfr/periodic/sampling/jfrThreadSampling.hpp"
#include "runtime/javaThread.hpp"

inline bool Jfr::has_sample_request(JavaThread* jt) {
  assert(jt != nullptr, "invariant");
  JfrThreadLocal* tl = jt->jfr_thread_local();
  return tl->has_sample_request() || tl->has_cpu_time_jfr_requests();
}

inline void Jfr::check_and_process_sample_request(JavaThread* jt) {
  if (has_sample_request(jt)) {
    JfrThreadSampling::process_sample_request(jt);
  }
}

#endif // SHARE_JFR_JFR_INLINE_HPP
