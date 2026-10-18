# Editor save lifecycle

This note describes how the keymap editor is launched and when it persists component positions.
The resize and animation interaction below is a potential timing issue to verify, not a confirmed
root cause of components being saved at `(0,0)`.

## Launch paths

- **MainActivity:** `MainActivity.startEditor()` starts `EditorActivity` with the selected profile
  extra. `EditorActivity.onCreate()` creates an `EditorUI` in `START_EDITOR` mode and calls
  `open()`, which attaches the editor view and calls `loadKeymapAfterView()`.
- **Notification, overlay editor disabled:** `TouchPointer` creates an Activity PendingIntent for
  `EditorActivity` and includes the selected profile extra. This reaches the same Activity editor
  path as MainActivity.
- **Notification, overlay editor enabled:** `TouchPointer` creates a Service PendingIntent for
  `EditorService`. That PendingIntent currently does not include the selected profile extra.
  `EditorService.onStartCommand()` therefore falls back to the profile named `Default` when the
  extra is absent, then creates `EditorUI` in `START_EDITOR` mode and calls `open(true)`.

## Save call sites

`EditorUI.saveKeymap()` serializes each editor component's `dataLine` and stores the resulting
profile with the current key-container dimensions.

It is reached in two ways:

1. `hideView()` saves whenever the editor is in `START_EDITOR` mode. The Save menu action hides
   the editor, and `EditorActivity.onDestroy()` also calls `hideView()` if its editor is still
   present.
2. `loadKeymapAfterView()` installs a layout-change listener. Whenever the key container's bounds
   change and a profile has already been loaded, the listener saves the current components and
   then calls `reloadKeymap()` to rebuild them for the new dimensions.

## Possible resize and animation timing issue

`loadKeymap()` builds editor components from the profile data. Components animate their views to
their stored positions during construction; for example, regular keys animate for one second.
Their `dataLine` implementations read positions from the live views when `saveKeymap()` runs.

If the key container changes size after components have been created but before those animations
finish, the layout listener can serialize intermediate view positions, then reload from the just-
saved profile. If those views have not yet moved far from their initial positions, the saved values
could be near `(0,0)`. This is a plausible explanation for position resets that correlate with an
editor launch path that triggers a later container resize. The call sequence alone does not prove
that this timing occurs on a particular device or launch path.

When investigating a reproduction, compare container size-change events, component animation
completion, `saveKeymap()` calls, and the coordinates emitted by each component's `dataLine`.
