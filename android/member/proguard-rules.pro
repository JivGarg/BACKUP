# Debug builds are shrunk only to drop unused library code; class names stay readable.
-dontobfuscate

# kotlinx.serialization ships its own rules; these keep our generated serializers regardless.
-keepclassmembers @kotlinx.serialization.Serializable class app.backupcircle.** {
    *** Companion;
    *** serializer(...);
}
-keep,includedescriptorclasses class app.backupcircle.**$$serializer { *; }
