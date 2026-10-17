// Adapted from cline-termux-voice (same owner): android/app/src/main/kotlin/dev/cline/voice/ui/markdown/MarkdownPreview.kt
package dev.cline.termux.ui.markdown

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.ThemeMode

/** A reply that touches every construct the renderer knows, in both scripts. */
private val SampleReply = """
# Deployment finished

I wrote the **WireGuard** config to `S25-WireGuard` and restarted the *tunnel* service. See the
[quick start](https://www.wireguard.com/quickstart/) or open https://example.com/status for status.
The old ~~wg0.conf~~ is gone; the whole thing took ~2s.
这一行在中文中间换行
不会多出空格。

## What changed

1. Generated a new key pair
2. Updated the peer list:
   - `phone` → 10.0.0.2
   - `laptop` → 10.0.0.3
     - reachable over LAN
       - and over LTE
3. Restarted the service

Numbering can start anywhere:

8. A list that starts at eight
9. Keeps counting
10. And lines up at ten

- [x] Generate keys
- [ ] Open 51820/udp on the router

1. [x] Backed up the old config
2. [ ] Notify the other devices

A loose list:

- First item, with a second line that wraps across the screen so the hanging indent shows.

- Second item

> **Note:** if the phone sleeps, the tunnel may drop.
> Set Termux to Unrestricted under Settings → Battery.

### Commands

```bash
wg-quick down wg0 && wg-quick up wg0
sudo systemctl restart wg-quick@wg0.service --no-pager --full-output-with-a-very-long-flag
```

```go
func main() {
${'\t'}if err := run(); err != nil {
${'\t'}${'\t'}log.Fatal(err)
${'\t'}}
}
```

    indented code block
    second line

#### Logs

The log lives at `/data/data/com.termux/files/home/.cline/data/logs/cline.log`; follow it with `tail -f`.

```text
hub listening on 127.0.0.1:25463, one client registered, ready
```

| Device | Address | Latency (ms) |
|:-------|:-------:|-------------:|
| phone | 10.0.0.2 | 12 |
| laptop | 10.0.0.3 | 3 |
| an old router in the living room whose system has not been updated for a very long time | 10.0.0.1 | 1 |

---

![topology](https://example.com/topology.png) and <kbd>Ctrl</kbd>+<kbd>C</kbd>
Line one with a hard break\
line two. Relative links like [Markdown.kt](app/src/Markdown.kt) stay plain text.

<details>raw html block</details>
""".trimIndent()

@Preview(name = "Light", widthDp = 400, heightDp = 2600, showBackground = true)
@Preview(name = "Dark", widthDp = 400, heightDp = 2600, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MarkdownTextPreview() {
    ClinePreviewTheme {
        Surface {
            MarkdownText(SampleReply, Modifier.padding(16.dp))
        }
    }
}

/** A reply still arriving: the last paragraph and the open code fence are the re-parsed tail. */
private val StreamingReply = """
## Restarting the tunnel

I stopped the service and wrote the new peer list.

- `phone` → 10.0.0.2
- `laptop` → 10.0.0.3

```bash
wg-quick down wg0
wg-quick up w
""".trimIndent()

@Preview(name = "Not selectable (code still selectable), light", widthDp = 400, heightDp = 420, showBackground = true)
@Preview(
    name = "Not selectable (code still selectable), dark",
    widthDp = 400,
    heightDp = 420,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MarkdownTextNotSelectablePreview() {
    ClinePreviewTheme {
        Surface {
            MarkdownText(StreamingReply.substringBefore("```") + "```bash\nwg-quick down wg0\n```", Modifier.padding(16.dp), selectable = false)
        }
    }
}

@Preview(name = "Streaming, light", widthDp = 400, heightDp = 420, showBackground = true)
@Preview(name = "Streaming, dark", widthDp = 400, heightDp = 420, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MarkdownTextStreamingPreview() {
    ClinePreviewTheme {
        Surface {
            MarkdownText(StreamingReply, Modifier.padding(16.dp), selectable = false, streaming = true)
        }
    }
}

@Preview(name = "Link safety, light", widthDp = 400, heightDp = 400)
@Preview(name = "Link safety, dark", widthDp = 400, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LinkSafetyDialogPreview() {
    ClinePreviewTheme {
        LinkSafetyDialog(url = "https://github.com.evil.example/login?next=%2Fsettings", onOpen = {}, onDismiss = {})
    }
}

/** An English answer: a table, a list and a quote that keep their left-to-right layout in any app direction. */
private val LtrReply = """
The peers after the restart:

| Field | Value |
|:------|------:|
| phone | 10.0.0.2 |
| laptop | 10.0.0.3 |

- Keys regenerated
- Service restarted

> The tunnel drops when the phone sleeps.
""".trimIndent()

/** An Arabic answer (the reply's own direction is right-to-left), opening with a code block that stays LTR. */
private val RtlReply = """
```bash
wg-quick up wg0
```

أعدت تشغيل النفق `wg0` بنجاح.

| الجهاز | العنوان |
|:-------|-------:|
| الهاتف | 10.0.0.2 |
| الحاسوب | 10.0.0.3 |

- تم إنشاء المفاتيح
- تمت إعادة تشغيل الخدمة

> قد ينقطع النفق عندما ينام الهاتف.
""".trimIndent()

@Preview(name = "LTR answer in an RTL app, light", widthDp = 400, heightDp = 520, showBackground = true, locale = "ar")
@Preview(
    name = "LTR answer in an RTL app, dark",
    widthDp = 400,
    heightDp = 520,
    showBackground = true,
    locale = "ar",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MarkdownTextLtrInRtlAppPreview() {
    ClinePreviewTheme {
        Surface {
            MarkdownText(LtrReply, Modifier.padding(16.dp))
        }
    }
}

/** Every heading level over body text, inline code and a code block: the hierarchy at each font size. */
private val HeadingsReply = """
# Deployment finished

The tunnel is up again; `wg0` answers on 51820/udp.

## What changed

The peer list was rewritten and the keys were rotated.

### Commands

Run `wg-quick up wg0` after a reboot -> it reads `/etc/wireguard/wg0.conf`.

#### Logs

```text
hub listening on 127.0.0.1:25463 => ready
```
""".trimIndent()

/** The DS §2.4 font size setting, which scales the body and so the headings with it. */
@Composable
private fun FontSizePreviewTheme(fontSize: Int, dark: Boolean, content: @Composable () -> Unit) {
    ClineTheme(
        appearance = AppearancePrefs(
            themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light,
            dynamicColor = false,
            fontSize = fontSize,
        ),
        content = content,
    )
}

@Preview(name = "Font size 12, light", widthDp = 400, heightDp = 520, showBackground = true)
@Composable
private fun MarkdownHeadingsSmallLightPreview() {
    FontSizePreviewTheme(FontSizeSetting.MIN, dark = false) {
        Surface { MarkdownText(HeadingsReply, Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Font size 12, dark", widthDp = 400, heightDp = 520, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MarkdownHeadingsSmallDarkPreview() {
    FontSizePreviewTheme(FontSizeSetting.MIN, dark = true) {
        Surface { MarkdownText(HeadingsReply, Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Font size 15, light", widthDp = 400, heightDp = 600, showBackground = true)
@Composable
private fun MarkdownHeadingsDefaultLightPreview() {
    FontSizePreviewTheme(FontSizeSetting.DEFAULT, dark = false) {
        Surface { MarkdownText(HeadingsReply, Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Font size 15, dark", widthDp = 400, heightDp = 600, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MarkdownHeadingsDefaultDarkPreview() {
    FontSizePreviewTheme(FontSizeSetting.DEFAULT, dark = true) {
        Surface { MarkdownText(HeadingsReply, Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Font size 20, light", widthDp = 400, heightDp = 760, showBackground = true)
@Composable
private fun MarkdownHeadingsLargeLightPreview() {
    FontSizePreviewTheme(FontSizeSetting.MAX, dark = false) {
        Surface { MarkdownText(HeadingsReply, Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Font size 20, dark", widthDp = 400, heightDp = 760, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MarkdownHeadingsLargeDarkPreview() {
    FontSizePreviewTheme(FontSizeSetting.MAX, dark = true) {
        Surface { MarkdownText(HeadingsReply, Modifier.padding(16.dp)) }
    }
}

@Preview(name = "RTL answer in an LTR app, light", widthDp = 400, heightDp = 560, showBackground = true)
@Preview(
    name = "RTL answer in an LTR app, dark",
    widthDp = 400,
    heightDp = 560,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MarkdownTextRtlInLtrAppPreview() {
    ClinePreviewTheme {
        Surface {
            MarkdownText(RtlReply, Modifier.padding(16.dp), selectable = false)
        }
    }
}
