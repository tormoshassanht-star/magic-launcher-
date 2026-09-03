# Magic Launcher

Android home-screen launcher inspired by Honor's Magic UI, with an app drawer that can be sorted by install date.

## Install

`MagicLauncher.apk` in this folder is the release build (signed with the debug key so it installs on any phone).

1. Copy the APK to the phone and open it. Allow "install from unknown sources" if asked.
2. Press the Home button and choose Magic Launcher, then "Always".
3. Optional: Launcher settings > Enable gesture service, to get double-tap-to-lock and swipe-down-for-notifications.

Requires Android 8.0 or newer.

## Features

- Home screen with pages, clock and date header with battery level, Google-style search bar, translucent dock.
- Long press an icon to drag it around, or use the popup (Remove, Add to dock, App info, Uninstall).
- Folders: drop one icon onto another to create a folder, drop more icons onto the folder to add them. Tap to open, edit the name at the top, drag an app out to move it back to Home. Drag to the right edge to move to the next page, or to the top "Remove" target to take it off Home. Drag from the app drawer straight onto Home too.
- Long press empty space for wallpaper, settings, add or remove pages.
- Widgets: long press empty space > Add widget, or long press an app > Widgets. Search box in the picker. Long press a widget to move it, or Resize from its popup.
- Page overview: pinch in on Home, or long press > Manage pages. Tap a page to open it, hold and drag to reorder, house icon sets the page the Home button returns to, bin removes it, plus adds one.
- Swipe up anywhere for the app drawer. Swipe down or press back to close it. Swipe down on the left half of Home for notifications, on the right half for the control panel.
- App drawer layout: pages you swipe sideways using the home grid (4 x 6 by default), or one long list. Settings > App drawer > Layout.
- Folders hold up to 20 apps.
- App drawer sort modes: Name, Newest installed, Oldest installed, Recently updated, Most used, Recently used.
  Date modes group apps under Today, Yesterday, This week, This month, then by month, and show the exact date under each app.
- Red dot on apps installed in the last 48 hours.
- Letter fast-scroll strip in Name mode.
- Search apps, then search the web or Play Store from the same box.
- Multi-select apps to uninstall, hide or add to Home in one go.
- Hidden apps list.
- Share your app list as text.
- Settings: grid size, icon shape (rounded, squircle, circle, square), icon size, labels, dock, drawer theme.

## Build

```
gradle assembleRelease
```

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35). `local.properties` points to the SDK.
