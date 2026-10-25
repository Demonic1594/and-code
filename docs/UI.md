# UI conventions

## Design tokens

Spacing, corner radii and shapes live in `app/src/main/java/com/yugahashimoto/andcode/ui/theme/Dimens.kt`:

| Token | Values |
|-------|--------|
| `Spacing` | `hairline` 2, `xs` 4, `sm` 8, `md` 12, `lg` 16, `xl` 20, `xxl` 24 dp; `screenEdge` = `lg` |
| `Radius` | `sm` 8, `md` 12, `lg` 16, `xl` 20, `pill` 100 dp |
| `AppShapes` | a `RoundedCornerShape` for each `Radius` step |

Colors come from `LocalThemeColors` and `MaterialTheme.colorScheme`, type from `MaterialTheme.typography`.

New UI takes its paddings, gaps and corners from these tokens instead of `N.dp` literals.
Older screens still carry off-scale values (5, 6, 10, 14, 18 dp); move them onto the scale when a
screen is redesigned, not as a drive-by change, because it shifts the layout.

## Screen files

Keep a screen file to the screen's own layout and state wiring. Composer, banners, status cards and
menus that only one screen uses go in sibling files in the same package (see `feature/chat/`:
`ChatComposer.kt`, `ChatComposerControls.kt`, `ChatBanners.kt`, `ChatStatusCards.kt`,
`ChatOverflowMenu.kt`; `ui/`: `GithubCloneDialog.kt`, `CrashReportDialog.kt`, `AppLabels.kt` next to
`AndCodeApp.kt`), marked `internal` only where another file needs them.
