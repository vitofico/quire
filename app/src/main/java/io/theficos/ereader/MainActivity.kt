package io.theficos.ereader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import io.theficos.ereader.ui.AppNavGraph
import io.theficos.ereader.ui.theme.EReaderTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EReaderTheme {
                AppNavGraph(container = (application as EReaderApp).container)
            }
        }
    }
}
