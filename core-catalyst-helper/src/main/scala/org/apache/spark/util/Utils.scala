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

package org.apache.spark.util

import java.io._
import java.net._
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Random

import com.google.common.collect.Interners
import org.apache.commons.lang3.SystemUtils
import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.network.util.JavaUtils

import scala.collection.JavaConverters._
import scala.collection.Map
import scala.util.Try
import scala.util.matching.Regex

/** CallSite represents a place in user code. It can have a short and a long form. */
private[spark] case class CallSite(shortForm: String, longForm: String)

private[spark] object CallSite {
  val SHORT_FORM = "callSite.short"
  val LONG_FORM = "callSite.long"
  val empty = CallSite("", "")
}

/**
 * Various utility methods used by Spark.
 */
private[spark] object Utils extends Logging {
  val random = new Random()

  @volatile private var cachedLocalDir: String = ""

  /**
   * Define a default value for driver memory here since this value is referenced across the code
   * base and nearly all files already use Utils.scala
   */
  val DEFAULT_DRIVER_MEM_MB = JavaUtils.DEFAULT_DRIVER_MEM_MB.toInt

  val MAX_DIR_CREATION_ATTEMPTS: Int = 10
  @volatile private var localRootDirs: Array[String] = null

  /** Scheme used for files that are locally available on worker nodes in the cluster. */
  val LOCAL_SCHEME = "local"

  /**
   * Whether the underlying operating system is Windows.
   */
  val isWindows = SystemUtils.IS_OS_WINDOWS

  /**
   * Whether the underlying operating system is Mac OS X.
   */
  val isMac = SystemUtils.IS_OS_MAC_OSX

  private val weakStringInterner = Interners.newWeakInterner[String]()

  private val PATTERN_FOR_COMMAND_LINE_ARG = "-D(.+?)=(.+)".r

  private var customHostname: Option[String] = sys.env.get("SPARK_LOCAL_HOSTNAME")
  private lazy val localIpAddress: InetAddress = findLocalInetAddress()

  private def findLocalInetAddress(): InetAddress = {
    val defaultIpOverride = System.getenv("SPARK_LOCAL_IP")
    if (defaultIpOverride != null) {
      InetAddress.getByName(defaultIpOverride)
    } else {
      val address = InetAddress.getLocalHost
      if (address.isLoopbackAddress) {
        // Address resolves to something like 127.0.1.1, which happens on Debian; try to find
        // a better address using the local network interfaces
        // getNetworkInterfaces returns ifs in reverse order compared to ifconfig output order
        // on unix-like system. On windows, it returns in index order.
        // It's more proper to pick ip address following system output order.
        val activeNetworkIFs = NetworkInterface.getNetworkInterfaces.asScala.toSeq
        val reOrderedNetworkIFs = if (isWindows) activeNetworkIFs else activeNetworkIFs.reverse

        for (ni <- reOrderedNetworkIFs) {
          val addresses = ni.getInetAddresses.asScala
            .filterNot(addr => addr.isLinkLocalAddress || addr.isLoopbackAddress).toSeq
          if (addresses.nonEmpty) {
            val addr = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(addresses.head)
            // because of Inet6Address.toHostName may add interface at the end if it knows about it
            val strippedAddress = InetAddress.getByAddress(addr.getAddress)
            // We've found an address that looks reasonable!
            logWarning("Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
              " a loopback address: " + address.getHostAddress + "; using " +
              strippedAddress.getHostAddress + " instead (on interface " + ni.getName + ")")
            logWarning("Set SPARK_LOCAL_IP if you need to bind to another address")
            return strippedAddress
          }
        }
        logWarning("Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
          " a loopback address: " + address.getHostAddress + ", but we couldn't find any" +
          " external IP address!")
        logWarning("Set SPARK_LOCAL_IP if you need to bind to another address")
      }
      address
    }
  }

  private[spark] val REDACTION_REPLACEMENT_TEXT = "*********(redacted)"

  /**
   * Redact the sensitive values in the given map. If a map key matches the redaction pattern then
   * its value is replaced with a dummy text.
   */
  def redact(conf: SparkConf, kvs: Seq[(String, String)]): Seq[(String, String)] = {
    val redactionPattern = conf.get(SECRET_REDACTION_PATTERN)
    redact(redactionPattern, kvs)
  }

  /**
   * Redact the sensitive values in the given map. If a map key matches the redaction pattern then
   * its value is replaced with a dummy text.
   */
  def redact[K, V](regex: Option[Regex], kvs: Seq[(K, V)]): Seq[(K, V)] = {
    regex match {
      case None => kvs
      case Some(r) => redact(r, kvs)
    }
  }

  /**
   * Redact the sensitive information in the given string.
   */
  def redact(regex: Option[Regex], text: String): String = {
    regex match {
      case None => text
      case Some(r) =>
        if (text == null || text.isEmpty) {
          text
        } else {
          r.replaceAllIn(text, REDACTION_REPLACEMENT_TEXT)
        }
    }
  }

  private def redact[K, V](redactionPattern: Regex, kvs: Seq[(K, V)]): Seq[(K, V)] = {
    // If the sensitive information regex matches with either the key or the value, redact the value
    // While the original intent was to only redact the value if the key matched with the regex,
    // we've found that especially in verbose mode, the value of the property may contain sensitive
    // information like so:
    // "sun.java.command":"org.apache.spark.deploy.SparkSubmit ... \
    // --conf spark.executorEnv.HADOOP_CREDSTORE_PASSWORD=secret_password ...
    //
    // And, in such cases, simply searching for the sensitive information regex in the key name is
    // not sufficient. The values themselves have to be searched as well and redacted if matched.
    // This does mean we may be accounting more false positives - for example, if the value of an
    // arbitrary property contained the term 'password', we may redact the value from the UI and
    // logs. In order to work around it, user would have to make the spark.redaction.regex property
    // more specific.
    kvs.map {
      case (key: String, value: String) =>
        redactionPattern.findFirstIn(key)
          .orElse(redactionPattern.findFirstIn(value))
          .map { _ => (key, REDACTION_REPLACEMENT_TEXT) }
          .getOrElse((key, value))
      case (key, value: String) =>
        redactionPattern.findFirstIn(value)
          .map { _ => (key, REDACTION_REPLACEMENT_TEXT) }
          .getOrElse((key, value))
      case (key, value) =>
        (key, value)
    }.asInstanceOf[Seq[(K, V)]]
  }

  /**
   * Looks up the redaction regex from within the key value pairs and uses it to redact the rest
   * of the key value pairs. No care is taken to make sure the redaction property itself is not
   * redacted. So theoretically, the property itself could be configured to redact its own value
   * when printing.
   */
  def redact(kvs: Map[String, String]): Seq[(String, String)] = {
    val redactionPattern = kvs.getOrElse(
      SECRET_REDACTION_PATTERN.key,
      SECRET_REDACTION_PATTERN.defaultValueString
    ).r
    redact(redactionPattern, kvs.toArray)
  }

  def redactCommandLineArgs(conf: SparkConf, commands: Seq[String]): Seq[String] = {
    val redactionPattern = conf.get(SECRET_REDACTION_PATTERN)
    commands.map {
      case PATTERN_FOR_COMMAND_LINE_ARG(key, value) =>
        val (_, newValue) = redact(redactionPattern, Seq((key, value))).head
        s"-D$key=$newValue"

      case cmd => cmd
    }
  }

  /** Serialize an object using Java serialization */
  def serialize[T](o: T): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(o)
    oos.close()
    bos.toByteArray
  }

  /** Deserialize an object using Java serialization */
  def deserialize[T](bytes: Array[Byte]): T = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bis)
    ois.readObject.asInstanceOf[T]
  }

  /** Deserialize an object using Java serialization and the given ClassLoader */
  def deserialize[T](bytes: Array[Byte], loader: ClassLoader): T = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bis) {
      override def resolveClass(desc: ObjectStreamClass): Class[_] = {
        // scalastyle:off classforname
        Class.forName(desc.getName, false, loader)
        // scalastyle:on classforname
      }
    }
    ois.readObject.asInstanceOf[T]
  }

  /** Deserialize a Long value */
  def deserializeLongValue(bytes: Array[Byte]) : Long = {
    // Note: we assume that we are given a Long value encoded in network (big-endian) byte order
    var result = bytes(7) & 0xFFL
    result = result + ((bytes(6) & 0xFFL) << 8)
    result = result + ((bytes(5) & 0xFFL) << 16)
    result = result + ((bytes(4) & 0xFFL) << 24)
    result = result + ((bytes(3) & 0xFFL) << 32)
    result = result + ((bytes(2) & 0xFFL) << 40)
    result = result + ((bytes(1) & 0xFFL) << 48)
    result + ((bytes(0) & 0xFFL) << 56)
  }

  /** String interning to reduce the memory usage. */
  def weakIntern(s: String): String = {
    weakStringInterner.intern(s)
  }

  /**
   * Get the ClassLoader which loaded Spark.
   */
  def getSparkClassLoader: ClassLoader = getClass.getClassLoader

  /**
   * Get the Context ClassLoader on this thread or, if not present, the ClassLoader that
   * loaded Spark.
   *
   * This should be used whenever passing a ClassLoader to Class.ForName or finding the currently
   * active loader when setting up ClassLoader delegation chains.
   */
  def getContextOrSparkClassLoader: ClassLoader =
    Option(Thread.currentThread().getContextClassLoader).getOrElse(getSparkClassLoader)

  /** Determines whether the provided class is loadable in the current thread. */
  def classIsLoadable(clazz: String): Boolean = {
    Try { classForName(clazz, initialize = false) }.isSuccess
  }

  // scalastyle:off classforname
  /**
   * Preferred alternative to Class.forName(className), as well as
   * Class.forName(className, initialize, loader) with current thread's ContextClassLoader.
   */
  def classForName[C](
      className: String,
      initialize: Boolean = true,
      noSparkClassLoader: Boolean = false): Class[C] = {
    if (!noSparkClassLoader) {
      Class.forName(className, initialize, getContextOrSparkClassLoader).asInstanceOf[Class[C]]
    } else {
      Class.forName(className, initialize, Thread.currentThread().getContextClassLoader).
        asInstanceOf[Class[C]]
    }
    // scalastyle:on classforname
  }

  /**
   * Run a segment of code using a different context class loader in the current thread
   */
  def withContextClassLoader[T](ctxClassLoader: ClassLoader)(fn: => T): T = {
    val oldClassLoader = Thread.currentThread().getContextClassLoader()
    try {
      Thread.currentThread().setContextClassLoader(ctxClassLoader)
      fn
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader)
    }
  }

  /**
   * Primitive often used when writing [[java.nio.ByteBuffer]] to [[java.io.DataOutput]]
   */
  def writeByteBuffer(bb: ByteBuffer, out: DataOutput): Unit = {
    if (bb.hasArray) {
      out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
    } else {
      val originalPosition = bb.position()
      val bbval = new Array[Byte](bb.remaining())
      bb.get(bbval)
      out.write(bbval)
      bb.position(originalPosition)
    }
  }

  /**
   * Primitive often used when writing [[java.nio.ByteBuffer]] to [[java.io.OutputStream]]
   */
  def writeByteBuffer(bb: ByteBuffer, out: OutputStream): Unit = {
    if (bb.hasArray) {
      out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
    } else {
      val originalPosition = bb.position()
      val bbval = new Array[Byte](bb.remaining())
      bb.get(bbval)
      out.write(bbval)
      bb.position(originalPosition)
    }
  }

  /**
   * JDK equivalent of `chmod 700 file`.
   *
   * @param file the file whose permissions will be modified
   * @return true if the permissions were successfully changed, false otherwise.
   */
  def chmod700(file: File): Boolean = {
    file.setReadable(false, false) &&
    file.setReadable(true, true) &&
    file.setWritable(false, false) &&
    file.setWritable(true, true) &&
    file.setExecutable(false, false) &&
    file.setExecutable(true, true)
  }

  /**
   * Create a directory given the abstract pathname
   * @return true, if the directory is successfully created; otherwise, return false.
   */
  def createDirectory(dir: File): Boolean = {
    try {
      // SPARK-35907: The check was required by File.mkdirs() because it could sporadically
      // fail silently. After switching to Files.createDirectories(), ideally, there should
      // no longer be silent fails. But the check is kept for the safety concern. We can
      // remove the check when we're sure that Files.createDirectories() would never fail silently.
      Files.createDirectories(dir.toPath)
      if ( !dir.exists() || !dir.isDirectory) {
        logError(s"Failed to create directory " + dir)
      }
      dir.isDirectory
    } catch {
      case e: Exception =>
        logError(s"Failed to create directory " + dir, e)
        false
    }
  }

  /**
   * Returns the current user name. This is the currently logged in user, unless that's been
   * overridden by the `SPARK_USER` environment variable.
   */
  def getCurrentUserName(): String = {
    Option(System.getenv("SPARK_USER"))
      .getOrElse(UserGroupInformation.getCurrentUser().getShortUserName())
  }

  /**
   * Returns the system properties map that is thread-safe to iterator over. It gets the
   * properties which have been set explicitly, as well as those for which only a default value
   * has been defined.
   */
  def getSystemProperties: Map[String, String] = {
    System.getProperties.stringPropertyNames().asScala
      .map(key => (key, System.getProperty(key))).toMap
  }

  /**
   * Convert a time parameter such as (50s, 100ms, or 250us) to seconds for internal use. If
   * no suffix is provided, the passed number is assumed to be in seconds.
   */
  def timeStringAsSeconds(str: String): Long = {
    JavaUtils.timeStringAsSec(str)
  }

  /**
   * Convert a time parameter such as (50s, 100ms, or 250us) to milliseconds for internal use. If
   * no suffix is provided, the passed number is assumed to be in ms.
   */
  def timeStringAsMs(str: String): Long = {
    JavaUtils.timeStringAsMs(str)
  }

  /**
   * Convert a passed byte string (e.g. 50b, 100k, or 250m) to bytes for internal use.
   *
   * If no suffix is provided, the passed number is assumed to be in bytes.
   */
  def byteStringAsBytes(str: String): Long = {
    JavaUtils.byteStringAsBytes(str)
  }

  /**
   * Convert a passed byte string (e.g. 50b, 100k, or 250m) to kibibytes for internal use.
   *
   * If no suffix is provided, the passed number is assumed to be in kibibytes.
   */
  def byteStringAsKb(str: String): Long = {
    JavaUtils.byteStringAsKb(str)
  }

  /**
   * Convert a passed byte string (e.g. 50b, 100k, or 250m) to mebibytes for internal use.
   *
   * If no suffix is provided, the passed number is assumed to be in mebibytes.
   */
  def byteStringAsMb(str: String): Long = {
    JavaUtils.byteStringAsMb(str)
  }

  /**
   * Convert a passed byte string (e.g. 50b, 100k, or 250m, 500g) to gibibytes for internal use.
   *
   * If no suffix is provided, the passed number is assumed to be in gibibytes.
   */
  def byteStringAsGb(str: String): Long = {
    JavaUtils.byteStringAsGb(str)
  }

  def stringToSeq(str: String): Seq[String] = {
    str.split(",").map(_.trim()).filter(_.nonEmpty)
  }

  /**
   * Get the local machine's FQDN.
   */
  def localCanonicalHostName(): String = {
    customHostname.getOrElse(localIpAddress.getCanonicalHostName)
  }
}
