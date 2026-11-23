# TangentAndroidSDK

The Android counterpart of [TangentSwiftSDK](https://github.com/Tangent-Apps/TangentSwiftSDK): Mixpanel, Adjust, Google Play Billing and Superwall, set up the same way in every Tangent Android app (PulseAI, TeamMeals, …).

| iOS (`TangentSwiftSDK`) | Android (`TangentSDK`) |
| --- | --- |
| `TangentSwiftSDK.shared.initialize(with:)` | `TangentSDK.initialize(application, configuration)` |
| `.analytics` (`AnalyticsService` + `MixpanelManager`) | `TangentSDK.analytics` |
| `AdjustManager` + `AdjustPaywallTracking` | `TangentSDK.adjust` + `AdjustPaywallTracking` |
| `.superwall` (`SuperwallManager`) | `TangentSDK.superwall` |
| StoreKit (handled by Superwall) | `TangentSDK.billing` (Play Billing) + `TangentSDK.subscriptions` |
| `PaywallAttribution` | `superwall.lastPaywallAttribution` |
| ATT (`tracking`) | — (Android has no ATT; Adjust reads the advertising ID itself) |

On Android the app owns the purchases (Superwall uses the SDK's purchase controller), so every purchase — Superwall's or a local paywall's — goes through `PlayBillingManager`, which is what reports it to Adjust once and tells Superwall the subscription status.

## Install

Served by **JitPack** from this public repo's git tags (like SPM on iOS): no account or token needed.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.Tangent-Apps.*") }
        }
    }
}
```

`gradle/libs.versions.toml`:

```toml
tangentSdk = "1.0.0"
tangent-sdk = { group = "com.github.Tangent-Apps", name = "TangentAndroidSDK", version.ref = "tangentSdk" }
```

`app/build.gradle.kts`: `implementation(libs.tangent.sdk)`. Mixpanel, Adjust, Play Billing, Superwall and coroutines come with it (`api`), so the app can drop its own declarations of them.

**Local development** (edit the SDK and an app together) — in the app's `settings.gradle.kts`:

```kotlin
includeBuild("../TangentSDKS/TangentAndroidSDK") {
    dependencySubstitution {
        substitute(module("com.github.Tangent-Apps:TangentAndroidSDK")).using(project(":tangent-sdk"))
    }
}
```

## Set up

In `Application.onCreate()`, after `super.onCreate()`:

```kotlin
TangentSDK.initialize(
    application = this,
    configuration = TangentSDK.Configuration(
        isDebug = BuildConfig.DEBUG,
        appVersion = BuildConfig.VERSION_NAME,
        mixpanelToken = Keys.MixPanel.value,
        adjust = TangentSDK.AdjustConfiguration(
            appToken = Keys.Adjust.value,
            paywallTracking = AdjustPaywallTracking(
                paywallViewedToken = "<paywall-viewed-token>",
                trialStartedToken = "<trial-started-token>",
                subscriptionStartedToken = "<subscription-started-token>",
                oneTimePurchaseToken = "<one-time-purchase-token>",
                takeHomeShare = remoteManager::adjustTakeHomeShare
            )
        ),
        superwall = Keys.SuperWall.value?.let { SuperwallManager.Configuration(apiKey = it) },
        subscriptions = SubscriptionManager.Configuration(productIds = setOf(PlayProducts.PREMIUM)),
        userId = { AccountId.uuidV5(ACCOUNT_NAMESPACE, authService.currentUID.filterNotNull().first()) }
    )
)
```

Each service stays off when its key is `null` or blank.

With Hilt, expose the services so view models can inject them:

```kotlin
@Module @InstallIn(SingletonComponent::class)
object TangentSDKModule {
    @Provides fun analytics() = TangentSDK.analytics
    @Provides fun billing() = TangentSDK.billing
    @Provides fun subscriptions() = TangentSDK.subscriptions
    @Provides fun superwall() = TangentSDK.superwall
    @Provides fun adjust() = TangentSDK.adjust
}
```

## Use

```kotlin
// Analytics — the app keeps its own sealed event list, implementing AnalyticsEvent.
TangentSDK.analytics.track(MixPanelEvents.PaywallShown(details))
TangentSDK.analytics.track("recipe_saved", mapOf("source" to "tiktok"))
TangentSDK.analytics.trackOnboardingStep(index, "goal")       // onboarding_[1.0]_3_goal_completed
TangentSDK.analytics.registerSuperProperties(mapOf("is_plus" to true))

// Paywalls
TangentSDK.superwall.present(
    placement = "campaign_trigger",
    onFinish = { didPurchase -> },
    onNotShown = { showLocalPaywall() }
)

// Local paywall
TangentSDK.adjust.paywallViewed()                             // Superwall's are reported automatically
val update = TangentSDK.billing.purchase(activity, details, offerToken, TangentSDK.accountId())

// Status
TangentSDK.subscriptions.isSubscribed                         // StateFlow<Boolean>
TangentSDK.subscriptions.setExternalEntitlement(serverSaysPlus)
```

What the SDK sends on its own:

- `app_installed` + `first_app_open` on first launch, `app_open` after, `app_updated` on a new version (turn off with `trackAppLaunches = false`).
- `platform` ("Android", unless the event sets its own) and `app_version` on every event.
- Adjust: sessions, every Superwall paywall opening, and every purchase made through `billing.purchase` (trial with no revenue; subscription / one-time product with price × take-home share). Analytics events whose names are in `AdjustConfiguration.eventTokens`.
- Superwall: subscription status (from `subscriptions.isEntitled`), identity (from `userId`) and the Adjust device IDs its server integration needs.

## Options worth knowing

**`SubscriptionManager.Configuration`**
- `acknowledge = false` when the server acknowledges subscriptions after verifying them (PulseAI). `hasUnacknowledgedSubscription` then says a sync is still owed.
- `forceSubscribed = BuildConfig.DEBUG && DebugOptions.FORCES_SUBSCRIPTION` to test premium screens.

**`SuperwallManager.Purchases`**
- `requireAccountId = true` refuses unstamped purchases (PulseAI: the server grants by account).
- `oneTimePurchase = { activity, details -> … }` for products the server must credit before they're consumed (PulseAI's packs).
- `onRestore = { packsStore.redeemUnconsumed() }` for the app's part of Restore.

**`PaywallScreen.Options`** (fixes for Superwall's paywall screen)
- `unlockScrolling` (on by default): scrolling inside paywalls with page scrolling off.
- `lightSystemBarIcons`: for dark paywalls.
- `fitSheetsToContent`: sheet paywalls at their content's height.
- `statusBarCssVariable = "--pulse-status-bar"`: the real status bar height for the page's top spacer.

**Custom actions**: `superwall.customActions` emits every custom action; `TapSequence(5, 3_000) { … }` turns them into a hidden reviewers' entry.

## Moving an app onto the SDK

| App code | Replaced by |
| --- | --- |
| `AdjustTracker`, `AdjustEventToken` | `AdjustConfiguration` + `TangentSDK.adjust` |
| `MixPanelLogger` (`log`, `logAppOpening`, `logAppUpdates`, `logOnboardingStep`, `setIsPlus`, `logRevenue`) | `TangentSDK.analytics` (`track`, automatic, automatic, `trackOnboardingStep`, `registerSuperProperties`, `trackRevenue`) |
| `PlayBillingManager` | `TangentSDK.billing` |
| `SubscriptionManager` | `TangentSDK.subscriptions` |
| `SuperwallService`, `SuperwallPurchaseController` | `SuperwallManager.Configuration` + `TangentSDK.superwall` |
| `AccountTokenMaker.token` / `SuperwallUserID.make` | `AccountId.uuidV5(namespace, uid)` (keep each app's namespace) |
| `SuperwallPaywallScroll` / `SuperwallSideSwipe`, `SuperwallDrawer`, `SuperwallStatusBarInset`, light bar icons | `PaywallScreen.Options` |

Keep these when moving:

- **Launch state**: pass `legacyLaunchState = LegacyLaunchState(userDefaults.hasOpenedAppBefore, userDefaults.previousAppVersion)` so existing users aren't counted as new installs.
- **Paywall views**: the SDK reports Superwall paywall *openings* to Adjust. Call `adjust.paywallViewed()` only for local paywalls (PulseAI's `PaywallPresenter` currently reports before presenting either kind — drop that call for Superwall).
- **Subscription status**: the SDK remembers it in its own preferences; on the first launch after moving it starts from `false` until Google Play answers (a second or so).

## Build

```bash
./gradlew :tangent-sdk:assembleRelease :tangent-sdk:testDebugUnitTest
```

## Releasing

Tag the commit with the new version and push the tag:

```bash
git tag -a 1.0.1 -m "TangentAndroidSDK 1.0.1" && git push origin 1.0.1
```

JitPack builds the version the first time an app asks for it (or open [jitpack.io/#Tangent-Apps/TangentAndroidSDK](https://jitpack.io/#Tangent-Apps/TangentAndroidSDK) and press **Get it** to build it ahead, and to read the build log). Bump `tangentSdkVersion` in `gradle.properties` too, for local builds. Don't move a tag once an app uses it: JitPack keeps the first build, so a fix is always a new tag.
