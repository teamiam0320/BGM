package com.iam.bgm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.iam.bgm.ui.BgmApp
import com.iam.bgm.ui.BgmViewModel

class MainActivity : ComponentActivity() {
    private val vm: BgmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BgmApp(vm) }
    }
}
