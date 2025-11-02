package com.app.nearil

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.app.nearil.ui.theme.NEARilTheme

// Tag for logging deep link data
private const val TAG = "MainActivity"

// 1. Define the domains you MUST open externally in a proper browser (to avoid Error 403)
private val OAUTH_HOSTS = listOf(
    "accounts.google.com",  // Google's sign-in host
    "accounts.facebook.com"
)

// 2. Define the Custom URI Scheme from your Rails HTML fallback
private const val DEEP_LINK_SCHEME = "nearilappscheme"
private const val APP_LINK_HOST = "nearil.com"
// CRITICAL FIX: The path must match the actual redirect URL path from your web app.
private const val AUTH_SUCCESS_PATH = "/app_auth_complete"

class MainActivity : ComponentActivity() {

    // Must be lateinit var to be assigned in the factory and accessed in handleIntent
    private lateinit var webView: WebView
    private var isAuthProcessing = false // Prevents multiple token exchange calls

    // Property to hold a URI if it arrives before the WebView is ready.
    private var pendingDeepLinkUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Process the initial intent (may contain a deep link from the OAuth flow)
        handleIntent(intent)

        setContent {
            NEARilTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Start URL should be the primary entry point of your web app
                    WebViewScreen(initialUrl = "https://${APP_LINK_HOST}")
                }
            }
        }
    }

    /**
     * Required override for activities with launchMode="singleTask" to handle new intents
     * when the activity is already running (like a deep link redirect).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Must call setIntent so later calls to getIntent() or handleIntent get the newest data
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Extracts and processes the deep link data for both HTTPS App Links and Custom Schemes.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.data == null) {
            Log.d(TAG, "No deep link data found.")
            return
        }

        val appLinkAction = intent.action
        val appLinkData = intent.data

        if (Intent.ACTION_VIEW == appLinkAction && appLinkData != null) {
            val fullUri = appLinkData.toString()
            Log.i(TAG, "Deep Link Caught: $fullUri")

            val scheme = appLinkData.scheme
            val host = appLinkData.host
            val path = appLinkData.path

            // Check if the URI is the success/failure path for EITHER the HTTPS App Link OR the Custom Scheme.
            val isAuthRedirect =
                (scheme == "https" && host == APP_LINK_HOST && path?.startsWith(AUTH_SUCCESS_PATH) == true) ||
                        (scheme == DEEP_LINK_SCHEME)

            if (isAuthRedirect) {
                if (isAuthProcessing) {
                    Log.w(TAG, "Ignored deep link: Already processing authentication.")
                    return
                }

                // CHECK 1: If WebView is initialized, process it immediately.
                if (::webView.isInitialized) {
                    processAuthDeepLink(appLinkData)
                } else {
                    // WebView not yet initialized, store the URI and wait.
                    Log.w(TAG, "WebView not yet initialized, storing deep link: $fullUri for later processing.")
                    pendingDeepLinkUri = appLinkData
                }

                // CRITICAL FIX: Clear the Intent data after processing to prevent
                // re-processing if the Activity is paused and resumed.
                intent.data = null
                setIntent(intent)

                // If the Auth Link was processed or stored, we are done.
                return
            }

            // --- Fallback: If it's another deep link we need to process (e.g., product/profile link) ---
            if (::webView.isInitialized) {
                // Load it in the WebView
                runOnUiThread {
                    webView.loadUrl(fullUri)
                }
            } else {
                Log.w(TAG, "WebView not yet initialized, cannot load deep link: $fullUri")
            }
        }
    }

    /**
     * Handles the successful or failed authentication deep link URI and redirects the WebView.
     */
    private fun processAuthDeepLink(appLinkData: Uri) {
        val authToken = appLinkData.getQueryParameter("auth_token")
        val error = appLinkData.getQueryParameter("error")
        val errorMessage = appLinkData.getQueryParameter("message") ?: ""

        // Clear the pending URI here if it was set, as it is now being processed.
        pendingDeepLinkUri = null

        if (authToken != null) {
            // --- SUCCESS PATH ---
            isAuthProcessing = true
            Log.i(TAG, "Authentication Success! Token received. Scheme: ${appLinkData.scheme}")

            val sessionCreationUrl = "https://${APP_LINK_HOST}/app/auth_login?token=$authToken"

            // REDIRECT:
            runOnUiThread {
                // Redirect the WebView to the Rails endpoint that will handle the login and session setup.
                webView.loadUrl(sessionCreationUrl)
                Log.i(TAG, "WebView redirected to session creation URL: $sessionCreationUrl")
            }

        } else if (error != null) {
            // --- FAILURE PATH ---
            Log.e(TAG, "Authentication Failed! Error: $error. Message: $errorMessage")

            // Redirect WebView to the main app URL to clear the blank screen.
            runOnUiThread {
                webView.loadUrl("https://${APP_LINK_HOST}/")
                Log.i(TAG, "WebView redirected to home page after failure.")
            }
        } else {
            Log.w(TAG, "Deep link received from known host, but missing auth_token or error parameter. Redirecting to home.")
            runOnUiThread {
                webView.loadUrl("https://${APP_LINK_HOST}/")
            }
        }
    }


    // Composable function to host the Android WebView
    @Composable
    fun WebViewScreen(initialUrl: String) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // Create a new WebView instance
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    // 1. CRITICAL: Set a custom User-Agent string to help the Rails server reliably
                    // distinguish this request from a standard web browser request.
                    settings.userAgentString = settings.userAgentString + " NEARilApp_AndroidWebView/1.0"

                    // 2. Store the WebView instance for later deep link handling
                    this@MainActivity.webView = this

                    // Check for and process any pending deep links immediately after initialization.
                    val uriToLoad = pendingDeepLinkUri

                    if (uriToLoad != null) {
                        // FIX: Changed uriToToLoad to uriToLoad
                        Log.i(TAG, "WebView initialized. Processing stored deep link: ${uriToLoad.toString()}")
                        // FIX: Changed uriToToLoad to uriToLoad
                        processAuthDeepLink(uriToLoad)
                    } else {
                        // Load the initial URL of your website only if no deep link was pending
                        loadUrl(initialUrl)
                    }


                    // 3. Implement the custom WebViewClient to intercept OAuth URLs
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url.toString()
                            return handleUrlLoading(view, url)
                        }

                        @Deprecated("Deprecated in API 24, but often used for compatibility")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return handleUrlLoading(view, url)
                        }

                        private fun handleUrlLoading(view: WebView?, url: String?): Boolean {
                            if (url == null) return false

                            val uri = Uri.parse(url)

                            // 1. CRITICAL FIX: Intercept OmniAuth Passthru and Inject Query Parameter
                            if (uri.host == APP_LINK_HOST && uri.path?.startsWith("/users/auth/") == true) {
                                // We assume any path starting with /users/auth/ is an OmniAuth passthru.
                                Log.i(TAG, "Intercepted OmniAuth Passthru: $url. INJECTING 'mobile_app=true'.")

                                // Build the new URL with the mobile_app=true flag
                                val modifiedUri = uri.buildUpon()
                                    .appendQueryParameter("mobile_app", "true")
                                    .build()

                                // Launch the external browser with the MODIFIED URL
                                val intent = Intent(Intent.ACTION_VIEW, modifiedUri)
                                view?.context?.startActivity(intent)

                                // Return true to indicate we handled the navigation, preventing the WebView from loading the passthru
                                return true
                            }

                            // 2. Check if the host requires external opening (Google/Facebook OAuth login page)
                            if (uri.host != null && OAUTH_HOSTS.any { uri.host!!.contains(it) }) {
                                Log.i(TAG, "Intercepted OAuth URL: $url. Launching external browser.")

                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                view?.context?.startActivity(intent)

                                // Return true to indicate we handled the navigation, preventing the WebView from loading it
                                return true
                            }

                            // 3. CRITICAL FIX: App Link (HTTPS) Handoff Page
                            if (uri.scheme == "https" && uri.host == APP_LINK_HOST && uri.path?.startsWith(AUTH_SUCCESS_PATH) == true) {
                                Log.i(TAG, "Intercepted verified App Link URL: $url. Handing off to Android OS resolver.")

                                // Launch the App Link URL as a new Intent to trigger the App Link resolver
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                view?.context?.startActivity(intent)

                                return true // Return TRUE to prevent the WebView from loading the URL
                            }

                            // 4. Check if the URL is the final deep link target (e.g., nearilappscheme://auth/...)
                            if (uri.scheme == DEEP_LINK_SCHEME) {
                                Log.i(TAG, "Allowing OS to resolve custom scheme URL: $url")
                                return false
                            }

                            // 5. For all other URLs (e.g., https://nearil.com/about), let the WebView load it.
                            return false
                        }
                    }
                }
            },
            update = {
                // Not strictly needed for a static website load, but included for completeness
            }
        )
    }
}

// Keeping the Greeting Composable and Preview for context/completeness
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NEARilTheme {
        // You would typically replace this with your Compose UI if not using a full WebView
    }
}
