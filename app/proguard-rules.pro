# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# the backup writes every setting's value: SettingDefaults pairs Settings.PREF_X (key) with Defaults.PREF_X by name
-keepclassmembers class helium314.keyboard.latin.settings.Settings { public static final java.lang.String PREF_*; }
-keepclassmembers class helium314.keyboard.latin.settings.Defaults { public static final *** PREF_*; }
