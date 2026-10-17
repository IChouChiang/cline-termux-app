package dev.cline.termux.ui.question

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.cline.termux.R
import dev.cline.termux.ui.approval.PendingInputBanner
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ClineIcons

/**
 * "Follow-up question" + "Answer" (composer-approvals.md C4.3; CA §9 item 2: the approval
 * banner with `contact_support`), shown while [QuestionUiState.bannerVisible]; docked 8dp
 * above `ComposerSurface`, below the approval banner when both exist (CA §0.4).
 */
@Composable
fun QuestionBanner(onAnswer: () -> Unit, modifier: Modifier = Modifier) {
    PendingInputBanner(
        icon = ClineIcons.ContactSupport,
        title = stringResource(R.string.question_banner_title),
        actionLabel = stringResource(R.string.question_banner_action),
        description = stringResource(R.string.question_banner_cd),
        onAction = onAnswer,
        modifier = modifier,
    )
}

@LightDarkPreviews
@Composable
private fun QuestionBannerPreview() {
    ComponentPreview { QuestionBanner(onAnswer = {}) }
}

@Preview(name = "Font scale 2, light", fontScale = 2f)
@Preview(name = "Font scale 2, dark", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun QuestionBannerLargeFontPreview() {
    ComponentPreview { QuestionBanner(onAnswer = {}) }
}
