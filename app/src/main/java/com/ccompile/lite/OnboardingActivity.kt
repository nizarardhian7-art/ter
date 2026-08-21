package com.ccompile.lite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ccompile.lite.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_ONBOARDED = "has_onboarded"

        fun isOnboarded(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDED, false)
        }

        fun setOnboarded(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDED, true).apply()
        }
    }

    private lateinit var binding: ActivityOnboardingBinding
    private val dots = mutableListOf<ImageView>()

    private data class OnboardingSlide(
        val iconRes: Int,
        val title: String,
        val subtitle: String
    )

    private val slides = listOf(
        OnboardingSlide(
            iconRes = R.drawable.ic_nav_terminal,
            title = "Linux Terminal on Your Phone",
            subtitle = "Full bash terminal with multi-session support, extra keys, and gesture controls. Ready to use out of the box."
        ),
        OnboardingSlide(
            iconRes = R.drawable.ic_nav_explorer,
            title = "File Manager & Extractor",
            subtitle = "Browse files, extract tar.gz / zip / 7z, run scripts, and install APKs — all from one place."
        ),
        OnboardingSlide(
            iconRes = R.drawable.ic_build,
            title = "Build & Compile",
            subtitle = "Compile projects and native libraries directly on your device. NDK support is available in Settings."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already onboarded — skip directly to MainActivity
        if (isOnboarded(this)) {
            goToMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupDots()
        setupButtons()
    }

    private fun setupViewPager() {
        binding.onboardingPager.adapter = OnboardingAdapter()
        binding.onboardingPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateButtonText(position)
            }
        })
    }

    private fun setupDots() {
        for (i in slides.indices) {
            val dot = ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@OnboardingActivity, R.drawable.bg_dot))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(8), dpToPx(8)
                ).apply {
                    setMargins(dpToPx(4), 0, dpToPx(4), 0)
                }
                alpha = if (i == 0) 1f else 0.3f
            }
            binding.dotContainer.addView(dot)
            dots.add(dot)
        }
    }

    private fun updateDots(position: Int) {
        dots.forEachIndexed { index, dot ->
            dot.alpha = if (index == position) 1f else 0.3f
            dot.scaleX = if (index == position) 1.2f else 1f
            dot.scaleY = if (index == position) 1.2f else 1f
        }
    }

    private fun setupButtons() {
        binding.btnSkip.setOnClickListener {
            finishOnboarding()
        }

        binding.btnNext.setOnClickListener {
            val current = binding.onboardingPager.currentItem
            if (current < slides.size - 1) {
                binding.onboardingPager.setCurrentItem(current + 1, true)
            } else {
                finishOnboarding()
            }
        }
    }

    private fun updateButtonText(position: Int) {
        binding.btnNext.text = if (position == slides.size - 1) {
            "Get Started"
        } else {
            "Next"
        }
        // Hide skip button on the last slide
        binding.btnSkip.visibility = if (position == slides.size - 1) View.INVISIBLE else View.VISIBLE
    }

    private fun finishOnboarding() {
        setOnboarded(this)
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ─── Adapter ───────────────────────────────────────────────

    private inner class OnboardingAdapter : RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder>() {

        inner class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.slideIcon)
            val title: TextView = view.findViewById(R.id.slideTitle)
            val subtitle: TextView = view.findViewById(R.id.slideSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding, parent, false)
            return SlideViewHolder(view)
        }

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            val slide = slides[position]
            holder.icon.setImageResource(slide.iconRes)
            holder.title.text = slide.title
            holder.subtitle.text = slide.subtitle
        }

        override fun getItemCount(): Int = slides.size
    }
}