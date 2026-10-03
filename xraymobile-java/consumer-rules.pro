# The upstream xraymobile AAR already ships the JNI keep rules for XrayCore,
# XrayCoreException and SocketProtector. The wrapper adds no native entry points.
-keep class org.xrayrust.mobile.compat.CoreController { public *; }
-keep interface org.xrayrust.mobile.compat.CoreCallbackHandler { *; }
