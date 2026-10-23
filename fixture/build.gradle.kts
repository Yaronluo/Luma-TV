plugins { id("com.android.application") }
android {
    namespace = "io.lumatv.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.lumatv.fixture"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("fixtureVersion").orElse("1").get().toInt()
        versionName = versionCode.toString()
    }
    flavorDimensions += "fixture"
    productFlavors {
        listOf("one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen").forEachIndexed { index, name ->
            create(name) {
                dimension = "fixture"
                applicationIdSuffix = ".$name"
                manifestPlaceholders["fixtureLabel"] = "Luma 验证 ${index + 1}"
            }
        }
    }
}
