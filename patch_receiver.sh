sed -i 's/launchApp(context, context.packageName)/\/\/ launchApp(context, context.packageName) \/\/ Disabled to prevent BAL block/g' app/src/main/java/com/example/appupdater/InstallReceiver.kt
