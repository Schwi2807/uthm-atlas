# Android SDK: accept licences + install packages (you do this once)

Accepting the licences means agreeing to Google's terms, so you have to do it yourself.
Pick **one** option.

## Option A: PowerShell (about 2 minutes)

1. Open a PowerShell window.
2. Paste this line, press Enter, and type `y` + Enter at every licence prompt:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; & "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

3. Paste this line and press Enter. It downloads about 200 MB, with no prompts:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; & "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## Option B: Android Studio

1. Start **Android Studio** from the Start menu.
2. In the first-run setup wizard, keep the defaults (Standard, SDK in `%LOCALAPPDATA%\Android\Sdk`).
3. On the **License Agreement** page, accept each licence, then click **Finish** and wait for the download.

When it's finished, tell Claude **"SDK done"**.
