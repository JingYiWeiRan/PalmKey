package com.jywr.pcbuapk.ui.activity

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 自定义竖屏扫码 Activity
 * 用于 zxing-android-embedded 的竖屏扫码模式
 * 
 * 竖屏通过 AndroidManifest.xml 中的 android:screenOrientation="portrait" 控制
 * 自动对焦由 zxing-android-embedded 库自动处理（Camera2 API 的 CONTINUOUS_PICTURE 模式）
 */
class PortraitCaptureActivity : CaptureActivity()
