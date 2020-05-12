package com.laotoua.dawnislandk

import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.laotoua.dawnislandk.data.entity.Forum
import com.laotoua.dawnislandk.data.state.AppState
import com.laotoua.dawnislandk.databinding.ActivityMainBinding
import com.laotoua.dawnislandk.ui.adapter.QuickNodeAdapter
import com.laotoua.dawnislandk.ui.popup.QuotePopup
import com.laotoua.dawnislandk.ui.util.ToolBarUtil.immersiveToolbarInitialization
import com.laotoua.dawnislandk.viewmodel.CommunityViewModel
import com.laotoua.dawnislandk.viewmodel.LoadingStatus
import com.laotoua.dawnislandk.viewmodel.SharedViewModel
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*


class MainActivity : AppCompatActivity(), QuickNodeAdapter.ForumClickListener {

    private lateinit var binding: ActivityMainBinding
    private val communityVM: CommunityViewModel by viewModels()
    private val sharedVM: SharedViewModel by viewModels()

    private val mAdapter =
        QuickNodeAdapter(this)

    private var doubleBackToExitPressedOnce = false
    private val mHandler = Handler()
    private val mRunnable = Runnable { doubleBackToExitPressedOnce = false }

    init {
        // load Resources
        lifecycleScope.launch { loadResources() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        immersiveToolbarInitialization()
        setContentView(binding.root)
        setUpForumDrawer()
    }


    // left forum drawer
    private fun setUpForumDrawer() {

        binding.forumRefresh.setOnClickListener {
            communityVM.refresh()
        }

        binding.forumContainer.layoutManager = LinearLayoutManager(this)
        binding.forumContainer.adapter = mAdapter

        communityVM.communityList.observe(this, Observer {
            mAdapter.setData(it)
            Timber.i("Loaded ${it.size} communities to Adapter")
            // TODO: set default forum
            sharedVM.setForum(it[0].forums[0])
            sharedVM.setForumNameMapping(communityVM.getForumNameMapping())
        })

        communityVM.loadingStatus.observe(this, Observer {
            if (it.getContentIfNotHandled()?.loadingStatus == LoadingStatus.FAILED) {
                Toast.makeText(this, it.peekContent().message, Toast.LENGTH_LONG)
                    .show()
            }
        })

    }

    // Forum Click
    override fun onForumClick(forum: Forum) {
        Timber.i("Clicked on Forum ${forum.name}")
        sharedVM.setForum(forum)
        binding.drawerLayout.closeDrawers()
    }


    // initialize Global resources
    private suspend fun loadResources() {
        AppState.loadCookies()

        // set default subscriptionID
        var feedId = MMKV.defaultMMKV().getString("feedId", null)
        if (feedId == null) {
            feedId = UUID.randomUUID().toString()
            MMKV.defaultMMKV().putString("feedId", feedId)
        }
        AppState.setFeedId(feedId)
    }

    override fun onBackPressed() {
        /**
         *  Catch for popup which failed to request focus
         */
        if (!QuotePopup.ensureQuotePopupDismissal()) return

        if (!doubleBackToExitPressedOnce &&
            findNavController(R.id.navHostFragment).previousBackStackEntry == null
        ) {
            doubleBackToExitPressedOnce = true
            Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
            mHandler.postDelayed(mRunnable, 2000)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        mHandler.removeCallbacks(mRunnable)
    }
}
