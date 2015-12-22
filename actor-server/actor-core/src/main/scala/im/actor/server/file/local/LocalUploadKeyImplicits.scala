package im.actor.server.file.local

import im.actor.server.file.LocalUploadKey

object LocalUploadKeyImplicits extends LocalUploadKeyImplicits

trait LocalUploadKeyImplicits {
  implicit class LocalUploadKeyCompanion(companion: com.trueaccord.scalapb.GeneratedMessageCompanion[LocalUploadKey]) {
    def fileKey(fileId: Long): LocalUploadKey = LocalUploadKey(s"upload_${fileId}")
    def partKey(fileId: Long, partNumber: Int) = LocalUploadKey(s"upload_${fileId}_${partNumber}")
  }
}
