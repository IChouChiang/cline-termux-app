package dev.cline.termux.ui.components

import android.content.res.Configuration

/**
 * "Hardware keyboard attached" (composer-approvals.md C1.10): a QWERTY keyboard that is not
 * hidden (Tab S7+ Book Cover, DeX). The one predicate for keyboard highlights and shortcut
 * hints, shared by the composer and the suggestion panel so the two never disagree.
 */
fun Configuration.hasHardwareKeyboard(): Boolean =
    keyboard == Configuration.KEYBOARD_QWERTY && hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
