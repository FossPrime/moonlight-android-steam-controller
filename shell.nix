{ pkgs ? import <nixpkgs> { config.android_sdk.accept_license = true; } }:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    cmdLineToolsVersion = "8.0";
    platformToolsVersion = "35.0.2";
    buildToolsVersions = [ "34.0.0" ];
    platformVersions = [ "34" ];
    abiVersions = [ "armeabi-v7a" "arm64-v8a" ];
    includeNDK = true;
    ndkVersions = [ "27.0.12077973" ];
  };
in
pkgs.mkShell {
  buildInputs = [
    pkgs.openjdk17
    androidComposition.androidsdk
  ];

  ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk";
  NDK_HOME = "${androidComposition.androidsdk}/libexec/android-sdk/ndk/27.0.12077973";

  shellHook = ''
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
  '';
}
