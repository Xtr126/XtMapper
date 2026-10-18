# Editor save lifecycle

This note describes how the keymap editor is launched and when it persists component positions.
The resize and animation interaction below is a potential timing issue to verify, not a confirmed
root cause of components being saved at `(0,0)`.

## Launch paths

- **MainActivity:** `MainActivity.startEditor()` starts `EditorActivity` with the selected profile
  extra. `EditorActivity.onCreate()` creates an `EditorUI` in `START_EDITOR` mode and calls
  `open()`, which attaches the editor view and calls `loadKeymapAndObserveSizeChanges()`.
- **Notification:** `TouchPointer` uses the `editorOverlay` setting to choose how the editor is
  presented: as an application overlay through `EditorService`, or as an Activity through
  `EditorActivity`. The Activity PendingIntent includes the selected profile. The Service
  PendingIntent currently does not, so `EditorService.onStartCommand()` falls back to the profile
  named `Default` when that extra is absent.
- **Keyboard shortcut:** `KeyEventHandler.handleKeyboardShortcuts()` calls
  `IRemoteServiceCallback.launchEditor()`, implemented by `TouchPointer` to start `EditorService`
  with the selected profile extra. `EditorService` uses the same `editorOverlay` setting to present
  the editor as an application overlay or forward to `EditorActivity`.

## Save call sites

`EditorUI.saveKeymap()` serializes each editor component's data with `toConfigLine()` and stores
the resulting profile with the current key-container dimensions.

It is reached in two ways:

1. `closeEditor()` saves whenever the editor is in `START_EDITOR` mode, then tears down the editor UI:
   it unregisters listeners, destroys the settings view, removes the editor view, and invokes the
   `onEditorClosed()` callback that finishes the Activity or stops the editor service. The Save
   menu action starts this teardown, and `EditorActivity.onDestroy()` also calls `closeEditor()` if
   its editor is still present. `closeEditor()` is not a reversible hide operation.
2. `loadKeymapAndObserveSizeChanges()` installs a layout-change listener. Whenever the key
   container's bounds change and a profile has already been loaded, the listener saves the current
   components and then calls `reloadKeymap()` to rebuild them for the new dimensions. The
   display-only `ShowKeymapService` uses this method before showing its overlay, so the view is not
   attached on every call path.

## Possible resize and animation timing issue

`loadKeymap()` builds editor components from the profile data. Components animate their views to
their stored positions during construction; for example, regular keys animate for one second.
Their serialization reads positions from the live views when `saveKeymap()` runs.

If the key container changes size after components have been created but before those animations
finish, the layout listener can serialize intermediate view positions, then reload from the just-
saved profile. If those views have not yet moved far from their initial positions, the saved values
could be near `(0,0)`. This is a plausible explanation for position resets that correlate with an
editor launch path that triggers a later container resize. The call sequence alone does not prove
that this timing occurs on a particular device or launch path.

When investigating a reproduction, compare container size-change events, component animation
completion, `saveKeymap()` calls, and the coordinates emitted when each component is serialized.
