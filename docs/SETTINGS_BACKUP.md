# Settings backup format

Backup & restore → Settings exports Calendula's settings to a JSON file and
imports one back (#481). Events are not part of it; they have the `.ics`
backup. This file is the schema's source of truth. The envelope and the
`settings` section come from floret-kit's `core-backup`
(`BackupFormat`, `PrefsSection`); `data/backup/SettingsBackup*.kt` implements
the rest.

## Top level

```json
{
  "format": "calendula-settings",
  "version": 1,
  "exportedAt": 1700000000000,
  "appVersion": "2.25.0",
  "settings": { … },
  "calendars": [ … ],
  "app": { … }
}
```

- `format` must be exactly `"calendula-settings"`, or the file is not a
  settings backup.
- `version` is `1`. A higher version is refused as "from a newer Calendula";
  a change that an older build could misread bumps it.
- `exportedAt` (epoch ms) and `appVersion` are informational.
- The file is limited to 8 MiB.

## Parsing rules

- **Unknown fields are ignored**, at any level, so a file from a later build
  imports as far as this one understands it.
- **Known fields are strict.** A known key holding the wrong JSON type rejects
  the whole file before anything is written.
- **Values are not range-checked.** They are stored as they are and go through
  the same reads every setting already does (clamps, enum fallbacks), so an
  out-of-range or unknown value degrades to the nearest valid one or the
  default.

## Import semantics

An import **replaces** every setting the backup covers; it does not merge. A
backed-up setting the file doesn't carry is reset to its default (that is what
the exporting install had). Everything is written in one DataStore edit. After
that the import refreshes what reads settings outside DataStore: the
contact-dates mirror (turned on and synced, or off and torn down, as the
settings toggle does), the reminder scan, the automatic-backup schedule, the
launcher name and the app language. Theme and widgets follow the stored
values on their own. A summary lists what could not be imported.

## `settings`

The settings stored under their own on-disk key names, in their stored form,
e.g. `"theme_mode": "DARK"`, `"multi_day_count": 4`,
`"default_reminder_minutes": "10,60"`. Only keys that have been set are
written. `SettingsBackupSchema.plain` lists them: appearance, fonts,
views, the agenda screen, the event form, reminders, automatic backup
(switch and interval) and contact special dates (switch, types, title
templates, year).

Three are taken only where this device can honour them, otherwise the current
value stays and the summary says why:

- `brand_font` / `plain_font` set to `custom`: the font file is not in the
  backup, so it is taken only if this install already has one for that role.
- `special_dates_enabled` on: needs the contacts permission here.
- `auto_backup_enabled` on: needs a backup folder chosen on this install.

## `calendars`

Per-calendar settings are stored by provider id, which means nothing on
another device. Each calendar with any of them is written with a portable
identity instead:

| Field | Type | Notes |
|---|---|---|
| `account_name` | String | Required. |
| `account_type` | String | Required. |
| `display_name` | String | Required. |
| `special_dates` | String? | `Birthday`/`Anniversary`/`Custom` for a contact-dates mirror calendar. |
| `hidden` | Boolean | Hidden in the drawer's filter. |
| `last_used` | Boolean | Preselected for new events. |
| `event_duration_minutes` | Int? | Per-calendar default length. |
| `reminders` | Int[]? | Timed reminder override; `[]` is "no reminder". |
| `all_day_reminders` | Int[]? | All-day reminder override. |

On import an entry matches the one calendar here with the same account name,
account type and display name. No match, or more than one, skips it and the
summary names it. A mirror calendar matches by `special_dates` instead, since
its name is localised; when the mirror has not created it yet, it is matched
after the import's sync, and its all-day reminders are applied through the
mirror so its existing events follow. An id in the prefs whose calendar no
longer exists is left out of the export.

## `app`

| Field | Type | Notes |
|---|---|---|
| `language` | String? | The app language tag; `null` follows the system. Absent: left as is. A language this build doesn't ship is skipped. |
| `launcher_name` | String? | `CALENDULA` or `CALENDAR`. Unknown: left as is. |

## Not exported

`SettingsBackupSchema.excluded`, each for a reason: this install's own state,
bookkeeping, or meaningless on another device.

- Font stamps (`*_font_stamp`): cache keys for the local font file.
- `agenda_widget_range`, `widget_size`: legacy defaults for unconfigured
  widgets. Widget configuration is not part of the backup at all.
- `recent_time_zones`: usage history.
- Onboarding progress and `visibility_notice_pending`.
- `auto_backup_folder_uri`: a SAF grant does not survive a reinstall or
  another device.
- Run status: `auto_backup_last_*`, `auto_backup_failures`,
  `special_dates_last_run`, `special_dates_stalled_reason`,
  `special_dates_last_foreground_sync`.
- `special_dates_calendar_*`: the mirror re-adopts its calendars by their
  provider marker.
- `disabled_calendar_ids`: switch-offs not yet written to this device's
  provider. Whether a calendar is switched on lives in the provider.
- `reminder_last_scan_millis`: the reminder delivery watermark.

`SettingsBackupSchemaTest` fails for any stored key that is in none of the
three lists, so a new setting has to be placed before it ships.
