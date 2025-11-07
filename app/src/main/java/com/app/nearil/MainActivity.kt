package com.app.nearil

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient 
import android.webkit.ValueCallback 
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher 
import androidx.activity.result.contract.ActivityResultContracts 
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

// 1. Define the domains you MUST open externally in a proper browser (ONLY GOOGLE)
private val OAUTH_HOSTS = listOf(
    "accounts.google.com" // Google's sign-in host
)

// 2. Define the Custom URI Scheme from your Rails HTML fallback
private const val DEEP_LINK_SCHEME = "nearilappscheme"
private const val APP_LINK_HOST = "nearil.com"
// The path must match the actual redirect URL path from your web app.
private const val AUTH_SUCCESS_PATH = "/app_auth_complete"

class MainActivity : ComponentActivity() {

    // Must be lateinit var to be assigned in the factory and accessed in handleIntent
    private lateinit var webView: WebView
    private var isAuthProcessing = false // Prevents multiple token exchange calls

    // Property to hold a URI if it arrives before the WebView is ready.
    private var pendingDeepLinkUri: Uri? = null

    // --- File Upload/Camera Variables ---
    // Callback to send the selected file(s) URI back to the WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    
    // Modern way to handle Activity Results (replaces onActivityResult)
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    // ------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {

        // --- File Chooser Launcher Registration (MUST be done before super.onCreate) ---
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // This logic handles the result from the file chooser intent
            var results: Array<Uri>? = null

            // Check if the result is OK and data exists
            if (result.resultCode == RESULT_OK) {
                result.data?.let { intent ->
                    // Handle single file selection
                    if (intent.dataString != null) {
                        results = arrayOf(Uri.parse(intent.dataString))
                    }
                    // Handle multiple file selection (if supported by intent)
                    else if (intent.clipData != null) {
                        val count = intent.clipData!!.itemCount
                        results = Array(count) { i -> intent.clipData!!.getItemAt(i).uri }
                    }
                }
            }

            // Deliver the result (even if null/canceled) back to the WebView
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null // Clear the callback
        }
        // ------------------------------------

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
                    // Allow file access for camera/gallery intents
                    settings.allowFileAccess = true 
                    settings.allowContentAccess = true

                    // 1. CRITICAL: Set a custom User-Agent string to help the Rails server reliably
                    // distinguish this request from a standard web browser request.
                    settings.userAgentString = settings.userAgentString + " NEARilApp_AndroidWebView/1.0"

                    // 2. Store the WebView instance for later deep link handling
                    this@MainActivity.webView = this

                    // Check for and process any pending deep links immediately after initialization.
                    val uriToLoad = pendingDeepLinkUri

                    if (uriToLoad != null) {
                        Log.i(TAG, "WebView initialized. Processing stored deep link: ${uriToLoad.toString()}")
                        processAuthDeepLink(uriToLoad)
                    } else {
                        // Load the initial URL of your website only if no deep link was pending
                        loadUrl(initialUrl)
                    }
                    
                    // --- Implement WebChromeClient for File/Camera Access ---
                    webChromeClient = object : WebChromeClient() {
                        
                        // This method is called when the web page wants to open a file chooser
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // 1. Check if there's already a pending callback (to prevent multiple choosers)
                            if (this@MainActivity.filePathCallback != null) {
                                this@MainActivity.filePathCallback!!.onReceiveValue(null)
                                this@MainActivity.filePathCallback = null
                            }

                            // 2. Store the new callback
                            this@MainActivity.filePathCallback = filePathCallback

                            // 3. Create the Intent from the params provided by the WebView
                            val intent = fileChooserParams?.createIntent()
                            try {
                                // 4. Launch the intent using the Activity Result Launcher
                                fileChooserLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Cannot launch file chooser: ${e.message}")
                                // If the launch fails, call the callback with null
                                this@MainActivity.filePathCallback?.onReceiveValue(null)
                                this@MainActivity.filePathCallback = null
                                return false
                            }

                            return true // Indicate that we handled the file chooser request
                        }

                        // Also include onGeolocationPermissionsShowPrompt for completeness if location is ever needed
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: android.webkit.GeolocationPermissions.Callback?
                        ) {
                            // NOTE: For production, you must implement a runtime permission check here.
                            callback?.invoke(origin, true, false)
                        }
                    }
                    // --- END WebChromeClient ---


                    // 3. Implement the custom WebViewClient to intercept OAuth URLs and App Links
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
                            val scheme = uri.scheme?.lowercase()
                            val host = uri.host

                            // 1. CRITICAL FIX: Intercept Facebook Sharer and force external opening using ACTION_SEND
                            if (host != null && (host.contains("facebook.com") || host.contains("m.facebook.com")) && uri.path?.contains("/sharer/sharer.php") == true) {
                                Log.i(TAG, "Intercepted Facebook Sharer URL: $url. Initiating ACTION_SEND.")

                                // Extract the URL to be shared from the 'u' query parameter
                                val sharedUrl = uri.getQueryParameter("u")

                                if (sharedUrl != null) {
                                    val sendIntent: Intent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, sharedUrl) // The link to share
                                        type = "text/plain"
                                    }

                                    // Create a chooser so the user can pick the best app (e.g., Facebook, WhatsApp, etc.)
                                    val shareIntent = Intent.createChooser(sendIntent, "Share Link")
                                    view?.context?.startActivity(shareIntent)
                                } else {
                                    Log.w(TAG, "Facebook Sharer URL missing 'u' parameter. Falling back to ACTION_VIEW.")
                                    // Fallback to simple ACTION_VIEW if we can't extract the share URL
                                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                                    view?.context?.startActivity(fallbackIntent)
                                }

                                // Return true to indicate we handled the navigation
                                return true
                            }

                            // 2. CRITICAL FIX: Intercept WhatsApp share scheme and use ACTION_SEND for reliability
                            if (scheme == "whatsapp") {
                                Log.i(TAG, "Intercepted WhatsApp URL: $url. Initiating ACTION_SEND.")
                                
                                // Extract the text parameter (which contains the URL to be shared)
                                val textToShare = uri.getQueryParameter("text")
                                
                                if (textToShare != null) {
                                    val sendIntent: Intent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, textToShare) 
                                        type = "text/plain"
                                    }
                                    
                                    // Use Intent.createChooser to ensure the OS handles the intent and the user can pick WhatsApp
                                    val shareIntent = Intent.createChooser(sendIntent, "Share via WhatsApp")
                                    view?.context?.startActivity(shareIntent)
                                } else {
                                    Log.w(TAG, "WhatsApp URL missing 'text' parameter. Falling back to ACTION_VIEW.")
                                    // Fallback: Use ACTION_VIEW which often resolves the whatsapp:// URI scheme directly
                                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                                    view?.context?.startActivity(fallbackIntent)
                                }
                                
                                return true // We handled the navigation
                            }

                            // 3. Check if the host requires external opening (Google OAuth login page)
                            if (host != null && OAUTH_HOSTS.any { host.contains(it) }) {
                                Log.i(TAG, "Intercepted Google OAuth URL: $url. Launching external browser.")

                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                view?.context?.startActivity(intent)

                                // Return true to indicate we handled the navigation, preventing the WebView from loading it
                                return true
                            }

                            // 4. CRITICAL FIX: App Link (HTTPS) Handoff Page
                            if (scheme == "https" && host == APP_LINK_HOST && uri.path?.startsWith(AUTH_SUCCESS_PATH) == true) {
                                Log.i(TAG, "Intercepted verified App Link URL: $url. Handing off to Android OS resolver.")

                                // Launch the App Link URL as a new Intent to trigger the App Link resolver
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                view?.context?.startActivity(intent)

                                return true // Return TRUE to prevent the WebView from loading the URL
                            }

                            // 5. FIX: Handle our own Custom Deep Link Scheme (nearilappscheme://)
                            // This MUST return false so the OS routes it to the current activity's onNewIntent handler.
                            if (scheme == DEEP_LINK_SCHEME) {
                                Log.i(TAG, "Allowing OS to handle custom scheme: $url. Returning false.")
                                return false 
                            }

                            // 6. Handle all other external links: Non-web schemes (mailto, tel, etc.)
                            val isExternalLaunchRequired = when (scheme) {
                                "http", "https" -> false // Let WebView handle standard web links
                                else -> true // All non-web schemes (mailto, sms, tel, etc.) must be external
                            }


                            if (isExternalLaunchRequired) {
                                Log.i(TAG, "Intercepted External Share/Non-Web Link: $url. Launching external OS handler.")
                                
                                // Create an intent to view the URI
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                
                                if (intent.resolveActivity(view?.context?.packageManager!!) != null) {
                                    view.context.startActivity(intent)
                                } else {
                                    Log.w(TAG, "No activity found to handle scheme for URL: $url")
                                }
                                
                                return true // We handled the loading, prevent the WebView from trying
                            }

                            // 7. For all other standard http/https links, let the WebView load it.
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
