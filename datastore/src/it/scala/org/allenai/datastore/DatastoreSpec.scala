package org.allenai.datastore

import org.allenai.common.Resource
import org.allenai.common.testkit.UnitSpec

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter

import scala.collection.JavaConversions._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

import java.nio.file.{Paths, StandardCopyOption, Path, Files}
import java.util.UUID
import java.util.zip.ZipFile

class DatastoreSpec extends UnitSpec {
  private val group = "org.allenai.datastore.test"

  private def copyTestFiles: Path = {
    // copy the zip file with the tests from the jar into a temp file
    val zipfile = Files.createTempFile("ai2-datastore-test", ".zip")
    TempCleanup.remember(zipfile)
    Resource.using(getClass.getResourceAsStream("testfiles.zip")) { is =>
      Files.copy(is, zipfile, StandardCopyOption.REPLACE_EXISTING)
    }

    // unzip the zipfile
    val zipdir = Files.createTempDirectory("ai2-datastore-test")
    TempCleanup.remember(zipdir)
    Resource.using(new ZipFile(zipfile.toFile)) { zipFile =>
      val entries = zipFile.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        val pathForEntry = zipdir.resolve(entry.getName)
        if(entry.isDirectory) {
          Files.createDirectories(pathForEntry)
        } else {
          Files.createDirectories(pathForEntry.getParent)
          Resource.using(zipFile.getInputStream(entry))(Files.copy(_, pathForEntry))
        }
      }
    }

    Files.delete(zipfile)
    TempCleanup.forget(zipfile)

    zipdir
  }

  def makeTestDatastore: Datastore = {
    val bucketname = "ai2-datastore-test-" + UUID.randomUUID().toString
    val config = new S3Config(bucketname)
    config.service.createBucket(bucketname)

    // Wait 20 seconds, because bucket creation is not instantaneous in S3
    Thread.sleep(20000)

    new Datastore(config)
  }

  def deleteDatastore(datastore: Datastore): Unit = {
    // delete the cache
    datastore.wipeCache()

    // delete everything in the bucket
    val s3 = datastore.s3config.service
    val bucket = datastore.s3config.bucket
    var listing = s3.listObjects(bucket)
    while(listing != null) {
      listing.getObjectSummaries.toList.foreach(summary =>
        s3.deleteObject(bucket, summary.getKey))

      listing = if (listing.isTruncated) s3.listNextBatchOfObjects(listing) else null
    }

    // delete the bucket
    s3.deleteBucket(bucket)
  }

  "DataStore" should "upload and download files" in {
    val testfilesDir = copyTestFiles
    val filenames = Seq("small_file_at_root.bin", "medium_file_at_root.bin", "big_file_at_root.bin")
    val datastore = makeTestDatastore
    try {
      for (filename <- filenames) {
        val fullFilenameString = testfilesDir.toString + "/" + filename
        datastore.publishFile(fullFilenameString, group, filename, 13)
      }

      for(filename <- filenames) {
        val path = datastore.filePath(group, filename, 13)
        assert(FileUtils.contentEquals(path.toFile, testfilesDir.resolve(filename).toFile))
      }
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "fail to download files that don't exist" in {
    val testfile = "medium_file_at_root.bin"
    val testfilesDir = copyTestFiles
    val datastore = makeTestDatastore
    try {
      datastore.publishFile(testfilesDir.resolve(testfile), group, testfile, 83)

      intercept[Datastore.DoesNotExistException] {
        datastore.filePath(group, testfile, 13)
      }

      intercept[Datastore.DoesNotExistException] {
        datastore.filePath(group, testfile + ".does_not_exist", 83)
      }

      intercept[Datastore.DoesNotExistException] {
        datastore.filePath(group + ".does_not_exist", testfile, 83)
      }
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "download the same file only once even if requested twice" in {
    val testfile = "big_file_at_root.bin"
    val testfilesDir = copyTestFiles
    val datastore = makeTestDatastore
    try {
      val testfilePath = testfilesDir.resolve(testfile)
      datastore.publishFile(testfilePath, group, testfile, 83)

      def downloadAndCheckFile(): Unit = {
        val path = datastore.filePath(group, testfile, 83)
        assert(FileUtils.contentEquals(path.toFile, testfilePath.toFile))
      }

      def time(f: => Unit) = {
        val start = System.nanoTime()
        f
        System.nanoTime() - start
      }

      val firstTime = time(downloadAndCheckFile)
      val secondTime = time(downloadAndCheckFile)
      assert(firstTime > secondTime)
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "download the same file only once even if requested twice in parallel" in {
    val testfile = "big_file_at_root.bin"
    val testfilesDir = copyTestFiles
    val datastore = makeTestDatastore
    try {
      val testfilePath = testfilesDir.resolve(testfile)
      datastore.publishFile(testfilePath, group, testfile, 83)

      def downloadAndCheckFile(): Unit = {
        val path = datastore.filePath(group, testfile, 83)
        assert(path.toFile.length() === testfilePath.toFile.length())
      }

      def time(f: => Unit) = {
        val start = System.nanoTime()
        f
        System.nanoTime() - start
      }

      val delayInMs: Long = 250
      val firstTime = future { time(downloadAndCheckFile) }
      Thread.sleep(delayInMs)
      val secondTime = future { time(downloadAndCheckFile) }

      // The second one has to wait for the first one to finish before it can
      // finish, so it should be slower.
      assert(Await.result(firstTime, 10 minutes) < Await.result(secondTime, 10 minutes) + delayInMs * 1000000)
    } finally {
      deleteDatastore(datastore)
    }
  }

  it should "upload and download a directory" in {
    def listOfFiles(dir: Path) =
      FileUtils.listFilesAndDirs(
        dir.toFile,
        TrueFileFilter.INSTANCE,
        TrueFileFilter.INSTANCE).toList.sorted.map { p =>
          dir.relativize(p.toPath)
        }

    val testfilesDir = copyTestFiles
    val testfiles = listOfFiles(testfilesDir)
    val datastore = makeTestDatastore
    try {
      datastore.publishDirectory(testfilesDir, group, "TestfilesDir", 11)

      val datastoreDir = datastore.directoryPath(group, "TestfilesDir", 11)
      val datastoreFiles = listOfFiles(datastoreDir)

      assert(testfiles === datastoreFiles)
    } finally {
      deleteDatastore(datastore)
    }
  }
}
