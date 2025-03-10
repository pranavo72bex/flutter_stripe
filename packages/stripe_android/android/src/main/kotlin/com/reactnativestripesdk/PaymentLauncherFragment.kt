package com.reactnativestripesdk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.stripe.android.ApiResultCallback
import com.stripe.android.Stripe
import com.stripe.android.model.*
import com.stripe.android.payments.paymentlauncher.PaymentLauncher
import com.stripe.android.payments.paymentlauncher.PaymentResult

/**
 * Instances of this class should only be initialized with the companion's helper methods.
 */
class PaymentLauncherFragment(
  private val context: ReactApplicationContext,
  private val stripe: Stripe,
  private val publishableKey: String,
  private val stripeAccountId: String?,
  private val promise: Promise,
  // Used when confirming a payment intent
  private val paymentIntentClientSecret: String? = null,
  private val confirmPaymentParams: ConfirmPaymentIntentParams? = null,
  // Used when confirming a setup intent
  private val setupIntentClientSecret: String? = null,
  private val confirmSetupParams: ConfirmSetupIntentParams? = null,
  // Used when handling the next action on a payment intent
  private val handleNextActionClientSecret: String? = null,
) : Fragment() {
  private lateinit var paymentLauncher: PaymentLauncher

  companion object {
    /**
     * Helper-constructor used for confirming payment intents
     */
    fun forPayment(context: ReactApplicationContext,
                   stripe: Stripe,
                   publishableKey: String,
                   stripeAccountId: String?,
                   promise: Promise,
                   paymentIntentClientSecret: String,
                   confirmPaymentParams: ConfirmPaymentIntentParams): PaymentLauncherFragment {
      val paymentLauncherFragment = PaymentLauncherFragment(
        context,
        stripe,
        publishableKey,
        stripeAccountId,
        promise,
        paymentIntentClientSecret = paymentIntentClientSecret,
        confirmPaymentParams = confirmPaymentParams
      )
      addFragment(paymentLauncherFragment, context, promise)
      return paymentLauncherFragment
    }

    /**
     * Helper-constructor used for confirming setup intents
     */
    fun forSetup(context: ReactApplicationContext,
                 stripe: Stripe,
                 publishableKey: String,
                 stripeAccountId: String?,
                 promise: Promise,
                 setupIntentClientSecret: String,
                 confirmSetupParams: ConfirmSetupIntentParams): PaymentLauncherFragment {
      val paymentLauncherFragment = PaymentLauncherFragment(
        context,
        stripe,
        publishableKey,
        stripeAccountId,
        promise,
        setupIntentClientSecret = setupIntentClientSecret,
        confirmSetupParams = confirmSetupParams
      )
      addFragment(paymentLauncherFragment, context, promise)
      return paymentLauncherFragment
    }

    /**
     * Helper-constructor used for handling the next action on a payment intent
     */
    fun forNextAction(context: ReactApplicationContext,
                      stripe: Stripe,
                      publishableKey: String,
                      stripeAccountId: String?,
                      promise: Promise,
                      handleNextActionClientSecret: String): PaymentLauncherFragment {
      val paymentLauncherFragment = PaymentLauncherFragment(
        context,
        stripe,
        publishableKey,
        stripeAccountId,
        promise,
        handleNextActionClientSecret = handleNextActionClientSecret,
      )
      addFragment(paymentLauncherFragment, context, promise)
      return paymentLauncherFragment
    }

    private fun addFragment(fragment: PaymentLauncherFragment, context: ReactApplicationContext, promise: Promise) {
      (context.currentActivity as? AppCompatActivity)?.let {
        try {
          it.supportFragmentManager.beginTransaction()
            .add(fragment, "payment_launcher_fragment")
            .commit()
        } catch (error: IllegalStateException) {
          promise.resolve(createError(ErrorType.Failed.toString(), error.message))
        }
      } ?: run {
        promise.resolve(createMissingActivityError())
      }
    }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                            savedInstanceState: Bundle?): View {
    paymentLauncher = createPaymentLauncher()
    if (paymentIntentClientSecret != null && confirmPaymentParams != null) {
      paymentLauncher.confirm(confirmPaymentParams)
    } else if (setupIntentClientSecret != null && confirmSetupParams != null) {
      paymentLauncher.confirm(confirmSetupParams)
    } else if (handleNextActionClientSecret != null) {
      paymentLauncher.handleNextActionForPaymentIntent(handleNextActionClientSecret)
    } else {
      throw Exception("Invalid parameters provided to PaymentLauncher. Ensure that you are providing the correct client secret and setup params (if necessary).")
    }
    return FrameLayout(requireActivity()).also {
      it.visibility = View.GONE
    }
  }

  private fun createPaymentLauncher(): PaymentLauncher {
    return PaymentLauncher.create(this, publishableKey, stripeAccountId) { paymentResult ->
      when (paymentResult) {
        is PaymentResult.Completed -> {
          if (paymentIntentClientSecret != null) {
            retrievePaymentIntent(paymentIntentClientSecret, stripeAccountId)
          } else if (handleNextActionClientSecret != null) {
            retrievePaymentIntent(handleNextActionClientSecret, stripeAccountId)
          } else if (setupIntentClientSecret != null) {
            retrieveSetupIntent(setupIntentClientSecret, stripeAccountId)
          } else {
            throw Exception("Failed to create Payment Launcher. No client secret provided.")
          }
        }
        is PaymentResult.Canceled -> {
          promise.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), message = null))
          cleanup()
        }
        is PaymentResult.Failed -> {
          promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), paymentResult.throwable))
          cleanup()
        }
      }
    }
  }

  private fun cleanup() {
    (context.currentActivity as? AppCompatActivity)?.supportFragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
  }

  private fun retrieveSetupIntent(clientSecret: String, stripeAccountId: String?) {
    stripe.retrieveSetupIntent(clientSecret, stripeAccountId, object : ApiResultCallback<SetupIntent> {
      override fun onError(e: Exception) {
        promise.resolve(createError(ConfirmSetupIntentErrorType.Failed.toString(), e))
        cleanup()
      }

      override fun onSuccess(result: SetupIntent) {
        when (result.status) {
          StripeIntent.Status.Succeeded,
          StripeIntent.Status.Processing,
          StripeIntent.Status.RequiresConfirmation,
          StripeIntent.Status.RequiresCapture -> {
            promise.resolve(createResult("setupIntent", mapFromSetupIntentResult(result)))
          }
          StripeIntent.Status.RequiresAction -> {
            if (isNextActionSuccessState(result.nextActionType)) {
              promise.resolve(createResult("setupIntent", mapFromSetupIntentResult(result)))
            } else {
              (result.lastSetupError)?.let {
                promise.resolve(createError(ConfirmSetupIntentErrorType.Canceled.toString(), it))
              } ?: run {
                promise.resolve(createError(ConfirmSetupIntentErrorType.Canceled.toString(), "Setup has been canceled"))
              }
            }
          }
          StripeIntent.Status.RequiresPaymentMethod -> {
            promise.resolve(createError(ConfirmSetupIntentErrorType.Failed.toString(), result.lastSetupError))
          }
          StripeIntent.Status.Canceled -> {
            promise.resolve(createError(ConfirmSetupIntentErrorType.Canceled.toString(), result.lastSetupError))
          }
          else -> {
            promise.resolve(createError(ConfirmSetupIntentErrorType.Unknown.toString(), "unhandled error: ${result.status}"))
          }
        }
        cleanup()
      }
    })
  }

  private fun retrievePaymentIntent(clientSecret: String, stripeAccountId: String?) {
    stripe.retrievePaymentIntent(clientSecret, stripeAccountId, object : ApiResultCallback<PaymentIntent> {
      override fun onError(e: Exception) {
        promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), e))
        cleanup()
      }

      override fun onSuccess(result: PaymentIntent) {
        when (result.status) {
          StripeIntent.Status.Succeeded,
          StripeIntent.Status.Processing,
          StripeIntent.Status.RequiresConfirmation,
          StripeIntent.Status.RequiresCapture -> {
            promise.resolve(createResult("paymentIntent", mapFromPaymentIntentResult(result)))
          }
          StripeIntent.Status.RequiresAction -> {
            if (isNextActionSuccessState(result.nextActionType)) {
              promise.resolve(createResult("paymentIntent", mapFromPaymentIntentResult(result)))
            } else {
              (result.lastPaymentError)?.let {
                promise.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), it))
              } ?: run {
                promise.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), "The payment has been canceled"))
              }
            }
          }
          StripeIntent.Status.RequiresPaymentMethod -> {
            promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), result.lastPaymentError))
          }
          StripeIntent.Status.Canceled -> {
            promise.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), result.lastPaymentError))
          }
          else -> {
            promise.resolve(createError(ConfirmPaymentErrorType.Unknown.toString(), "unhandled error: ${result.status}"))
          }
        }
        cleanup()
      }
    })
  }

  /**
   * Check if paymentIntent.nextAction is out-of-band, such as voucher-based or waiting
   * on customer verification. If it is, then being in this state is considered "successful".
   */
  private fun isNextActionSuccessState(nextAction: StripeIntent.NextActionType?): Boolean {
    return when (nextAction) {
      StripeIntent.NextActionType.DisplayOxxoDetails,
      StripeIntent.NextActionType.VerifyWithMicrodeposits -> true
      StripeIntent.NextActionType.RedirectToUrl,
      StripeIntent.NextActionType.UseStripeSdk,
      StripeIntent.NextActionType.AlipayRedirect,
      StripeIntent.NextActionType.BlikAuthorize,
      StripeIntent.NextActionType.WeChatPayRedirect,
      null -> false
    }
  }
}

