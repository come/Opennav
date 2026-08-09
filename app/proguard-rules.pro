# MapLibre reaches into its own layer, source and expression classes from JNI, so R8 must
# not rename or remove them.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# The depth arithmetic is small, hot and safety-critical; leaving its names intact makes
# a crash report from a boat readable.
-keep class org.opennav.core.depth.** { *; }
-keep class org.opennav.core.geo.** { *; }
