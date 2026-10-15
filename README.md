# LayerTrace

LayerTrace is a page-layout editor for Android, in the spirit of InDesign and Affinity Publisher. It runs on tablets, foldables and phones. It is built and tested for the **Samsung Galaxy Tab S7 FE** (including DeX) and the **Galaxy Z Fold** (cover screen and inner screen, flat, tabletop and book postures).

## File formats: IDML and `.indd`

| Format | Support |
|---|---|
| **IDML** (`.idml`, InDesign Markup) | Open, edit and save. This is LayerTrace's native format. |
| **`.indd`** (native InDesign) | Import preview: page count and size, swatches, font and link lists, and the page 1 preview on a reference layer. Frames and text can't be read. |
| **PDF** | Export (vector, RGB, embedded fonts, optional bleed). |
| **PNG** | Export the current page at 150 ppi. |

`.indd` is Adobe's closed, undocumented binary database format. No published spec or open-source reader exists. Affinity Publisher and [DesignCraft](https://github.com/storytold/designcraft) also import IDML only. For a full conversion, open the file in InDesign and choose **File › Save As › InDesign Markup (IDML)**. Files saved by LayerTrace are IDML, and both InDesign and Affinity Publisher open IDML.

**Import preview.** InDesign embeds an XMP metadata packet in every `.indd`. When you open an `.indd`, LayerTrace can build a new document from it with:
- the same page count and page size;
- the document's named swatches;
- InDesign's preview image of page 1, if the file has one, on a locked, non-printing layer called "INDD preview (reference)", so you can rebuild the layout over it.

It also lists the fonts and linked files the document uses. The result is an ordinary unsaved IDML document.

When you save, content that LayerTrace doesn't edit yet is carried through unchanged where possible. That includes tables, anchored objects, special characters, buttons, object styles, text wrap, XML tags, metadata and unknown attributes. Stories you haven't edited are written back byte-for-byte.

## Features (Milestone 1)

**Documents**
- New-document presets (A4/A5/A3, Letter, Legal, Tabloid, cards, social, slides).
- Document setup: size, facing pages, margins, columns and bleed.
- Recent files, plus autosave with crash recovery.

**Pages and structure**
- Spreads, with pages you can add, delete, move and assign a master to.
- Master pages: rendered under document pages, editable, and you can create new ones.
- Layers: visibility, lock, colour, print flag and order.
- Ruler guides: drag them out of the rulers.

**Frames and shapes**
- Text frames, graphic frames, rectangles, ellipses, polygons and lines.
- Move, resize (8 handles; Shift keeps proportions, Alt resizes from the centre) and rotate.
- Flip, align, distribute, arrange, group/ungroup and lock.
- The Direct Selection tool edits path points.
- Snapping to pages, margins, columns, guides and other objects.

**Text**
- Type directly on the canvas, with an IME/soft keyboard and hardware keyboard.
- Text flows through threaded frames and columns, with an overset indicator.
- Paragraph and character styles, plus local overrides:
  - Character: font, style, size, leading, tracking, colour, underline, strike, caps and baseline shift.
  - Paragraph: alignment, indents, and space before/after.
- Import TTF/OTF fonts (File › Import Font). Missing fonts are substituted and reported.

**Colour and images**
- Swatches: CMYK, RGB, LAB, spot, tints and gradients (gradients render only).
- Fill and stroke, stroke weight, opacity and corner radius.
- Placing an image (PNG, JPEG, WebP…) embeds it in the IDML so the file stays self-contained. Fit, fill and centre content.

**Editing**
- Unlimited undo/redo; typing is merged into single steps.

## Workspaces

Switch workspaces from **View › Switch Workspace** (Ctrl+F6) or the status bar. When a keyboard or mouse connects or disconnects, the app suggests a switch.

**Keyboard & Mouse (default)**
- Menu bar with shortcut hints, tool bar and docked panels.
- Hover highlighting, pointer cursors and right-click context menus.
- Wheel scrolls; Ctrl+wheel zooms at the cursor.
- Space-drag and middle-drag pan.
- InDesign-style shortcuts. Press **Ctrl+/** for the full list.

**Touch (milestone 1)**
- Same layout, with larger handles and hit areas.
- **Touch & hold** on an object, text or empty canvas opens its actions, including tool switching.
- One finger moves objects or pans empty canvas; two fingers pinch-zoom and pan.

## Layouts by screen

| Window | Layout |
|---|---|
| Tab S7 FE landscape, DeX, wide windows (≥ 840 dp) | Menu bar, left tool bar, canvas, docked panel column |
| Fold inner screen, tablet portrait (600–840 dp) | Menu bar, tool bar, canvas; panels dock on demand |
| Fold cover screen, phones (< 600 dp) | Compact top bar with overflow menu, bottom tool bar, panels in a bottom sheet |
| Fold half-open, horizontal hinge (tabletop) | Canvas above the hinge, tools and panels below |
| Fold half-open, vertical hinge (book) | Canvas on one side, panels on the other |

Layouts are chosen from the window size and the hinge, not from the device model, so split-screen and freeform windows work too. Zoom and selection survive folding and unfolding.

## Install on your devices

1. Open the repository's **Actions** tab, then the latest **Android build** run.
2. Download the **LayerTrace-apk-N** artifact and unzip it to get `app-debug.apk`.
3. Copy it to the tablet or phone, open it, and allow "install unknown apps" for your file manager.

Every build is signed with the same (non-secret) debug key in `keystore/`, so new builds install over old ones without uninstalling.

The **layout-screenshots** artifact contains rendered previews of the editor at the Tab S7 FE and Fold screen sizes.

## Things to check on real hardware

These can't be fully verified in CI:
- S Pen: works as a precise pointer. Pressure and hover tools come in a later milestone.
- DeX: resizing the window, and keyboard shortcuts with the Book Cover Keyboard.
- Fold: unfolding with a document open, tabletop/book postures, and the cover-screen soft keyboard while typing.
- Fonts: imported fonts in text frames, and PDF output in a PDF viewer.

## Build locally

You need JDK 17+ and the Android SDK (platform 36).

```
./gradlew :core:test                 # model + IDML tests (plain JVM)
./gradlew :app:recordRoborazziDebug  # app tests + layout screenshots
./gradlew :app:assembleDebug         # APK
```

## Project layout

| Path | Contents |
|---|---|
| `core/` | Pure Kotlin. Document model, IDML reader/writer (`idml/`), XML parser, geometry, editing operations (`edit/`), story editing and styles (`text/`). |
| `app/render/` | Android rendering: text layout across threaded frames, page renderer, PDF export. |
| `app/canvas/` | The document canvas: viewport, overlays, pointer/touch gestures. |
| `app/editor/` | View model, actions and shortcuts, keyboard and IME handling. |
| `app/workspace/` | Adaptive layouts (size classes and fold postures), welcome screen. |
| `app/ui/` | Menus, tool bars, panels, dialogs. |

## Roadmap

- **M2: Touch workspace.** Gesture-first UI, radial tool menus, S Pen pressure and hover, palm rejection, on-canvas transform fields.
- **M3: IDML fidelity.** Text wrap, tables, effects (shadows, feathers), master overrides, footnotes, object styles, anchored objects, bullets and numbering, hyphenation settings, OpenType features.
- **M4: Print.** CMYK/PDF-X export, preflight, package with links and fonts, colour management.

[DesignCraft](https://github.com/storytold/designcraft) (Rust, MIT/Apache-2.0) is a clean-room InDesign-style editor. It is the reference for M3 and M4: its Knuth–Plass paragraph composer, hyphenation and PDF/X-4 export.
