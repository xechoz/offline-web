package io.github.xechoz.offlineweb.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val viewModel: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OfflineWebDemoTheme {
                DemoScreen(
                    state = viewModel.state,
                    onUrlChange = viewModel::setUrl,
                    onLoad = viewModel::load,
                    onUpdate = viewModel::update,
                    onRemove = viewModel::removePackage,
                    onClear = viewModel::clearAll,
                )
            }
        }
    }
}
