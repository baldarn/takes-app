# Keep line numbers so Play Console stack traces can be mapped back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# CaptureController.originalSound reaches the recorder's media spec by this field, to record stereo at the
# original's rate where the camera library would follow the phone's camcorder profile.
-keepclassmembers class androidx.camera.video.Recorder$Builder { androidx.camera.video.MediaSpec$Builder mMediaSpecBuilder; }
