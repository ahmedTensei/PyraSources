package com.tensei.app.utils

import java.io.IOException

class CloudflareChallengeException(
    val challengeUrl: String,
    val sourceHost: String,
    override val message: String = "تم اكتشاف حماية Cloudflare على $sourceHost"
) : IOException(message)


