package io.legado.app.ui.about

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.lib.theme.filletBackground
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AboutActivity : BaseActivity<ActivityAboutBinding>() {
    override val binding by viewBinding(ActivityAboutBinding::inflate)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llAbout.background = filletBackground
        binding.tvAppSummary.setText(R.string.omi_about_summary)
        val tag = "aboutFragment"
        val fragment = supportFragmentManager.findFragmentByTag(tag) ?: AboutFragment()
        supportFragmentManager.beginTransaction().replace(R.id.fl_fragment, fragment, tag).commit()
    }
}
