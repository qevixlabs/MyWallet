-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*
# kotlinx.serialization
-keepclassmembers class **.*$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
