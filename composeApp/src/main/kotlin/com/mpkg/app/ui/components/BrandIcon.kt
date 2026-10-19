package com.mpkg.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

@Composable
fun BrandIcon(modifier: Modifier = Modifier) {
    Image(painterResource("icons/app_256.png"), "应用图标", modifier)
}
