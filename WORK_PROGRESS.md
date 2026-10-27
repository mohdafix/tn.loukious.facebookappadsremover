# Facebook v584 Contextual Download Actions - Work Progress & Handover

## Overview
This document summarizes the investigation, reverse-engineering discoveries, and implementation changes for fixing **"Show contextual download actions"** (native Story and Reel download buttons) on Facebook app update **v584.0.0.51.56** (`com.facebook.katana`).

---

## 1. Root Causes Discovered on Facebook v584

### A. Reel Button Factory Signature Shift
- In Facebook v578/v580, the button factory helper had 19 or 20 parameters where parameter 15 was an integer (flag).
- In Facebook v584, parameter 15 was changed to a **5th `Function1` callback**, followed by 3 boolean flags.
- **Exact signature**:
  ```
  LX/93B;-><init>(Lcom/facebook/auth/usersession/FbUserSession;LX/1ZH;LX/2oX;LX/2bz;LX/2bz;LX/9pV;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;ZZZ)V
  ```
- Previous signature checks failed with `IllegalStateException: Reel button factory has incompatible signature`.

### B. Inlined Button Factory Constructor
- Facebook inlined the previous static factory method directly into constructor `new X.93B(...)`.
- DexKit threw `IllegalArgumentException: DexMethod is constructor, not method` when trying to call `getMethodInstance` on `<init>`.
- Resolution: Handled `factory.isConstructor == true` by storing `Executable` and calling `newInstance(*args)` with an 8th proxy handler for slot 15.

### C. Inlined Marker Constructor
- In previous versions, the reel marker was a static method (`marker(iconEnum): markerObject`).
- In v584, marker is inlined into constructor `new X.93E(X.1Wz iconEnum)` (subclass of `X.9pV`).
- Resolution: Allowed `marker` to be an `Executable` (Constructor or static Method).

### D. Single-Collection Assembly
- In previous versions, the assembly call took two lists: `(List buttons, List markers)`.
- In v584, layout assembly is handled by Litho `Column.Builder` (`X.3T3.A03(builder, collection)`), passing a single collection of component elements.
- Resolution: Adjusted assembly discovery and injection to support `listIndices.size >= 1`, appending `downloadButton` to `buttons` and conditionally appending `downloadMarker` if a markers list is present.

### E. Component Hierarchy & Nested Player Params
- Component: `X.AF0` (`UDDSideBarComponent`) with render method `AF0.A0l(X.3tD) -> X.3SE`.
- In v584, `VideoPlayerParams` is nested: `AF0.A07` (`X.5FJ`) has field `A03` of type `com.facebook.video.engine.api.VideoPlayerParams`.
- Resolution: `ContextualDownloadHook` resolves both direct and nested player fields (`subPlayerField`).

### F. Negative Cache Lockout & Cache Schema
- When discovery previously threw an exception, it saved `absent-native-v8|<timestamp>` in `fbar_discovery_cache.xml`, locking out discovery for 72 hours.
- Resolution: Bumped cache schema to `CACHE_SCHEMA = 5` in `MethodCache.kt` and metadata prefix to `native-v9|` / `absent-native-v9|`.

---

## 2. Key Code Modifications

1. **`app/src/main/java/tn/loukious/facebookappadsremover/hooks/ReelFactorySignature.kt`**:
   - Accepts `Function1` at parameter index 15.
2. **`app/src/test/java/tn/loukious/facebookappadsremover/hooks/ReelFactorySignatureTest.kt`**:
   - Added unit tests for FB 584 signatures (all passing).
3. **`app/src/main/java/tn/loukious/facebookappadsremover/hooks/ContextualDownloadHook.kt`**:
   - Updated `ReelMetadata` and `ReelRuntime` to store `subPlayerField` and `markerClass`.
   - Supported constructor-based factories and markers.
   - Enhanced `assemblies` matcher to support single-collection methods (`X.3T3.A03`).
   - Enhanced component resolution to hook `AF0.A0l` and resolve nested `VideoPlayerParams`.
4. **`app/src/main/java/tn/loukious/facebookappadsremover/core/MethodCache.kt`**:
   - Bumped `CACHE_SCHEMA = 5`.
5. **`app/src/main/java/tn/loukious/facebookappadsremover/ModuleMain.kt`**:
   - Allowed `size in 2..4` for cached reel methods list.
6. **`app/src/main/java/tn/loukious/facebookappadsremover/hooks/AccountHook.kt`**:
   - Silenced logcat flooding by adding value change guards on token, user ID, and username updates.
7. **`app/src/main/java/tn/loukious/facebookappadsremover/hooks/DownloadHook.kt`**:
   - Improved regex parsing for `VideoId` in `VideoPlayerParams.toString()`.
   - Added fallback video URL extraction and null safety on URI fields.
8. **`app/build.gradle.kts`**:
   - Bumped `versionCode = 25`, `versionName = "1.24"`.

---

## 3. How to Resume and Test at Home

### Prerequisites
- Android device with root (KernelSU/Magisk/Apatch) and LSPosed installed.
- Facebook app update 584.0.0.51.56 installed.
- USB debugging enabled.

### Commands to Run
1. **Pull the latest changes from master/main**:
   ```bash
   git pull origin master
   ```

2. **Build and install the module APK**:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" # or path to JDK 17/21
   .\gradlew.bat assembleDebug
   adb install -r app\build\outputs\apk\debug\FacebookAppAdsRemover-v1.24-debug.apk
   ```

3. **Clear stale cache and restart Facebook**:
   ```powershell
   adb shell "su -c 'rm -f /data/data/com.facebook.katana/shared_prefs/fbar_discovery_cache.xml'"
   adb shell am force-stop com.facebook.katana
   adb logcat -c
   adb shell monkey -p com.facebook.katana -c android.intent.category.LAUNCHER 1
   ```

4. **Verify Discovery in Logcat**:
   ```powershell
   adb logcat -d | Select-String "FBAR.ContextDownload|native reel"
   ```
   Look for:
   ```
   native reel Download button installed via X.AF0.A0l
   ```

5. **Test in Facebook**:
   - Open any Reel.
   - Verify the **Download** button appears in the right sidebar.
   - Tap the button and verify the quality selection dialog opens to download the video.
