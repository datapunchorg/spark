/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage

import java.nio.{ByteBuffer, MappedByteBuffer}

import org.apache.commons.lang3.{JavaVersion, SystemUtils}
import sun.misc.Unsafe
import sun.nio.ch.DirectBuffer

import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils

/** Helper methods for storage-related objects. */
object StorageUtils extends Logging {

  // In Java 8, the type of DirectBuffer.cleaner() was sun.misc.Cleaner, and it was possible
  // to access the method sun.misc.Cleaner.clean() to invoke it. The type changed to
  // jdk.internal.ref.Cleaner in later JDKs, and the .clean() method is not accessible even with
  // reflection. However sun.misc.Unsafe added a invokeCleaner() method in JDK 9+ and this is
  // still accessible with reflection.
  private val bufferCleaner: DirectBuffer => Unit =
    if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_9)) {
      val cleanerMethod =
        Utils.classForName("sun.misc.Unsafe").getMethod("invokeCleaner", classOf[ByteBuffer])
      val unsafeField = classOf[Unsafe].getDeclaredField("theUnsafe")
      unsafeField.setAccessible(true)
      val unsafe = unsafeField.get(null).asInstanceOf[Unsafe]
      buffer: DirectBuffer => cleanerMethod.invoke(unsafe, buffer)
    } else {
      val cleanerMethod = Utils.classForName("sun.misc.Cleaner").getMethod("clean")
      buffer: DirectBuffer => {
        // Careful to avoid the return type of .cleaner(), which changes with JDK
        val cleaner: AnyRef = buffer.cleaner()
        if (cleaner != null) {
          cleanerMethod.invoke(cleaner)
        }
      }
    }

  /**
   * Attempt to clean up a ByteBuffer if it is direct or memory-mapped. This uses an *unsafe* Sun
   * API that will cause errors if one attempts to read from the disposed buffer. However, neither
   * the bytes allocated to direct buffers nor file descriptors opened for memory-mapped buffers put
   * pressure on the garbage collector. Waiting for garbage collection may lead to the depletion of
   * off-heap memory or huge numbers of open files. There's unfortunately no standard API to
   * manually dispose of these kinds of buffers.
   */
  def dispose(buffer: ByteBuffer): Unit = {
    if (buffer != null && buffer.isInstanceOf[MappedByteBuffer]) {
      logTrace(s"Disposing of $buffer")
      bufferCleaner(buffer.asInstanceOf[DirectBuffer])
    }
  }

}
