package com.yugahashimoto.andcode.ui.components

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.Spacing

private const val TAG = "WebPageRecovery"

/**
 * In place of a page whose renderer Android ended (see [WebPageRecovery]): what happened, and
 * [onReload], which builds a fresh WebView for it. [detail], the page's host say, goes under the title.
 */
@Composable
internal fun PageStoppedResponding(
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.webview_page_gone_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.lg),
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Text(
                stringResource(R.string.webview_page_gone_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.md).widthIn(max = 360.dp),
            )
            Button(onClick = onReload, modifier = Modifier.padding(top = Spacing.xl)) {
                Text(stringResource(R.string.webview_page_gone_reload))
            }
        }
    }
}

/**
 * From [android.webkit.WebViewClient.onRenderProcessGone]: takes this WebView, whose renderer is gone,
 * out of the view tree and destroys it, as Android requires of an app that carries on. It can never
 * draw again, and left in place it would keep every DevTools session on its page (the agent's
 * included) waiting for answers that will not come; destroying it ends them.
 */
internal fun WebView.discardAfterRendererGone(detail: RenderProcessGoneDetail?) {
    // A crash is the page's doing (or the engine's); otherwise Android ended it, to free memory.
    Log.w(TAG, if (detail?.didCrash() == true) "A page crashed its renderer" else "Android ended a page's renderer")
    setTag(R.id.webview_discarded, true)
    (parent as? ViewGroup)?.removeView(this)
    destroy()
}

/** From an AndroidView's onRelease: destroys [view] unless [discardAfterRendererGone] already has. */
internal fun destroyWebView(view: View) {
    if (view is WebView && view.getTag(R.id.webview_discarded) != true) view.destroy()
}
