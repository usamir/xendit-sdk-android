package com.xendit;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpStack;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.Volley;
import com.google.gson.JsonObject;

import com.xendit.DeviceInfo.GPSLocation;
import com.xendit.DeviceInfo.Model.DeviceLocation;
import com.xendit.Logger.Logger;
import com.xendit.Models.Authentication;
import com.xendit.Models.Card;
import com.xendit.Models.Token;
import com.xendit.Models.TokenConfiguration;
import com.xendit.Models.TokenCreditCard;
import com.xendit.Models.XenditError;
import com.xendit.DeviceInfo.AdInfo;
import com.xendit.DeviceInfo.DeviceInfo;
import com.xendit.network.BaseRequest;
import com.xendit.network.DefaultResponseHandler;
import com.xendit.network.NetworkHandler;
import com.xendit.network.TLSSocketFactory;
import com.xendit.network.errors.ConnectionError;
import com.xendit.network.errors.NetworkError;
import com.xendit.network.interfaces.ResultListener;
import com.xendit.utils.CardValidator;
import com.xendit.utils.PermissionUtils;

import java.io.UnsupportedEncodingException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import io.sentry.Sentry;
import io.sentry.SentryClient;
import io.sentry.android.AndroidSentryClientFactory;
import io.sentry.event.Event;
import io.sentry.event.helper.ShouldSendEventCallback;
import io.sentry.event.interfaces.ExceptionInterface;
import io.sentry.event.interfaces.SentryException;
import io.sentry.event.interfaces.SentryInterface;
import io.sentry.event.interfaces.SentryStackTraceElement;

/**
 * Created by Dimon_GDA on 3/7/17.
 */

public class Xendit {

    private static final String TAG = "Xendit";
    private static final String PRODUCTION_XENDIT_BASE_URL = "https://api.xendit.co";
    private static final String TOKENIZE_CREDIT_CARD_URL = "/cybersource/flex/v1/tokens?apikey=";
    private static final String CREATE_CREDIT_CARD_URL = PRODUCTION_XENDIT_BASE_URL + "/credit_card_tokens";
    private static final String GET_TOKEN_CONFIGURATION_URL = PRODUCTION_XENDIT_BASE_URL + "/credit_card_tokenization_configuration";
    private static final String DNS_SERVER = "https://182c197ad5c04f878fef7eab1e0cbcd6@sentry.io/262922";
    static final String ACTION_KEY = "ACTION_KEY";

    private Context context;
    private String publishableKey;
    private RequestQueue requestQueue;
    private ConnectivityManager connectivityManager;

    public static Logger mLogger;
    public Xendit(final Context context, String publishableKey) {
        this.context = context;
        this.publishableKey = publishableKey;

        // init logdna logger
        mLogger = new Logger(context, publishableKey);
        mLogger.log(Logger.Level.DEBUG, "Start debugging");

        // init sentry
        // Use the Sentry DSN (client key) from the Project Settings page on Sentry
        String sentryDsn = DNS_SERVER;
        Sentry.init(sentryDsn, new AndroidSentryClientFactory(context));
        // filter out exceptions
        shouldSendException();

        //get device info
        new Thread(new Runnable() {
            public void run() {
                try {
                    AdInfo adInfo = DeviceInfo.getAdvertisingIdInfo(context);
                    String advertisingId = adInfo.getId();
                    mLogger.log(Logger.Level.DEBUG, "ADID: " + advertisingId);
                } catch (Exception e) {
                    mLogger.log(Logger.Level.ERROR, e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
        mLogger.log(Logger.Level.DEBUG, "OS version: " + DeviceInfo.getOSVersion() + "\n OS API Level: " +
                 DeviceInfo.getAPILevel() + "\n Device: " + DeviceInfo.getDevice() +
                "\n Model (and Product): " + DeviceInfo.getModel() + " (" + DeviceInfo.getProduct() + ")"
        );
        if (DeviceInfo.getWifiSSID(context).equals("Does not have ACCESS_WIFI_STATE permission")) {
            mLogger.log(Logger.Level.DEBUG, "SSID: " + DeviceInfo.getWifiSSID(context));
        }
        mLogger.log(Logger.Level.DEBUG, "Language: " + DeviceInfo.getLanguage());
        mLogger.log(Logger.Level.DEBUG, "IP: " + DeviceInfo.getIPAddress(true));


        if(!PermissionUtils.hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
             mLogger.log(Logger.Level.DEBUG, "Access Fine Location permission is not granted");
        } else {
            GPSLocation gpsLocation = new GPSLocation(context);
            DeviceLocation deviceLocation = gpsLocation.getLocation();
            if (deviceLocation != null && deviceLocation.getLatitude() != null) {
                mLogger.log(Logger.Level.DEBUG, "Latitude: " + deviceLocation.getLatitude());
                mLogger.log(Logger.Level.DEBUG, "Longitude: " + deviceLocation.getLongitude());
            }
            mLogger.log(Logger.Level.DEBUG, "Latitude: " + gpsLocation.getLatitude());
            mLogger.log(Logger.Level.DEBUG, "Longitude: " + gpsLocation.getLongitude());
            if (gpsLocation.getLac(context) != 0) {
                mLogger.log(Logger.Level.DEBUG, "Lac: " + gpsLocation.getLac(context));
            }
            if (gpsLocation.getCid(context) != 0) {
                mLogger.log(Logger.Level.DEBUG, "Cid: " + gpsLocation.getCid(context));
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {

            HttpStack stack;
            try {
                stack = new HurlStack(null, new TLSSocketFactory());
            } catch (KeyManagementException e) {
                mLogger.log(Logger.Level.ERROR, e.getMessage());
                e.printStackTrace();
                stack = new HurlStack();
            } catch (NoSuchAlgorithmException e) {
                mLogger.log(Logger.Level.ERROR, e.getMessage());
                e.printStackTrace();
                stack = new HurlStack();
            }
            requestQueue = Volley.newRequestQueue(context, stack);
        } else {
            requestQueue = Volley.newRequestQueue(context);
        }

        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Determines whether the credit card number provided is valid
     *
     * @param  creditCardNumber A credit card number
     * @return true if the credit card number is valid, false otherwise
     * @deprecated Use CardValidator.isCardNumberValid
     */
    @Deprecated
    public static boolean isCardNumberValid(String creditCardNumber) {
        mLogger.log(Logger.Level.INFO, "isCardNumberValid");
        return CardValidator.isCardNumberValid(creditCardNumber);
    }

    /**
     * Determines whether the card expiration month and year are valid
     *
     * @param  cardExpirationMonth The month a card expired represented by digits (e.g. 12)
     * @param  cardExpirationYear The year a card expires represented by digits (e.g. 2026)
     * @return true if both the expiration month and year are valid
     * @deprecated Use CardValidator.isExpiryValid
     */
    @Deprecated
    public static boolean isExpiryValid(String cardExpirationMonth, String cardExpirationYear) {
        mLogger.log(Logger.Level.INFO,"isExpiryValid");
        return CardValidator.isExpiryValid(cardExpirationMonth, cardExpirationYear);
    }

    /**
     * Determines whether the card CVN is valid
     *
     * @param  creditCardCVN The credit card CVN
     * @return true if the cvn is valid, false otherwise
     * @deprecated Use CardValidator.isCvnValid
     */
    @Deprecated
    public static boolean isCvnValid(String creditCardCVN) {
        mLogger.log(Logger.Level.INFO, "isCvnValid");
        return CardValidator.isCvnValid(creditCardCVN);
    }

    /**
     * Creates a single-use token. 3DS authentication will be bundled into this method unless you
     * set shouldAuthenticate as false.
     *
     * @param  card A credit card
     * @param  amount The amount you will eventually charge. This value is used to display to the
     *                user in the 3DS authentication view.
     * @param tokenCallback The callback that will be called when the token creation completes or
     *                      fails
     */
    public void createSingleUseToken(final Card card, final int amount, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "createSingleUseToken");
        String amountStr = Integer.toString(amount);

        createSingleOrMultipleUseToken(card, amountStr, true, false, tokenCallback);
    }

    /**
     * Creates a single-use token. 3DS authentication will be bundled into this method unless you
     * set shouldAuthenticate as false.
     *
     * @param  card A credit card
     * @param  amount The amount you will eventually charge. This value is used to display to the
     *                user in the 3DS authentication view.
     * @param shouldAuthenticate A flag indicating if 3DS authentication are required for this token
     * @param tokenCallback The callback that will be called when the token creation completes or
     *                      fails
     */
    public void createSingleUseToken(final Card card, final int amount, final boolean shouldAuthenticate, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO,  "createSingleUseToken");
        String amountStr = Integer.toString(amount);

        createSingleOrMultipleUseToken(card, amountStr, shouldAuthenticate, false, tokenCallback);
    }

    /**
     * Creates a multiple-use token. Authentication must be created separately if shouldAuthenticate
     * is true.
     *
     * @param  card A credit card
     * @param tokenCallback The callback that will be called when the token creation completes or
     *                      fails
     */
    public void createMultipleUseToken(final Card card, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "createMultipleUseToken");
        createSingleOrMultipleUseToken(card, "0", false, true, tokenCallback);
    }

    /**
     * @deprecated As of v.2.0.0.
     * Replaced by {@link #createSingleUseToken(Card, int, boolean, TokenCallback)} for single use token
     * and {@link #createMultipleUseToken(Card, TokenCallback)} for multiple use token
     */
    @Deprecated
    public void createToken(final Card card, final String amount, final boolean isMultipleUse, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "createToken");
        createSingleOrMultipleUseToken(card, amount, true, isMultipleUse, tokenCallback);
    }

    private void createSingleOrMultipleUseToken(final Card card, final String amount, final boolean shouldAuthenticate, final boolean isMultipleUse, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "createSingleOrMultipleUseToken");
        if (card != null && tokenCallback != null) {
            if (!CardValidator.isCardNumberValid(card.getCreditCardNumber())) {
                mLogger.log(Logger.Level.ERROR, new XenditError(context.getString(R.string.create_token_error_card_number)).getErrorMessage());
                tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_card_number)));
                return;
            }

            if (!CardValidator.isExpiryValid(card.getCardExpirationMonth(), card.getCardExpirationYear())) {
                mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.create_token_error_card_expiration)).getErrorMessage());
                tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_card_expiration)));
                return;
            }

            if (card.getCreditCardCVN() != null && !CardValidator.isCvnValid(card.getCreditCardCVN())) {
                mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.create_token_error_card_cvn)).getErrorMessage());
                tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_card_cvn)));
                return;
            }

            if (card.getCreditCardCVN() != null && !CardValidator.isCvnValidForCardType(card.getCreditCardCVN(), card.getCreditCardNumber())) {
                mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.error_card_cvn_invalid_for_type)).getErrorMessage());
                tokenCallback.onError(new XenditError(context.getString(R.string.error_card_cvn_invalid_for_type)));
                return;
            }

            getTokenizationConfiguration(new NetworkHandler<TokenConfiguration>().setResultListener(new ResultListener<TokenConfiguration>() {
                @Override
                public void onSuccess(TokenConfiguration tokenConfiguration) {
                    tokenizeCreditCardRequest(tokenConfiguration, card, amount, shouldAuthenticate, isMultipleUse, tokenCallback);
                    mLogger.log(Logger.Level.DEBUG,  "Successfully tokenize configuration");
                }

                @Override
                public void onFailure(NetworkError error) {
                    mLogger.log(Logger.Level.ERROR,  error.getMessage());
                    tokenCallback.onError(new XenditError(error));
                }
            }));
        }
    }

    /**
     * Creates a 3DS authentication for a multiple-use token
     *
     * @param  tokenId The id of a multiple-use token
     * @param  amount The amount that will eventually be charged. This number is displayed to the
     *                user in the 3DS authentication view
     * @param authenticationCallback The callback that will be called when the authentication
     *                               creation completes or fails
     */
    public void createAuthentication(final String tokenId, final int amount, final AuthenticationCallback authenticationCallback) {
        mLogger.log(Logger.Level.INFO,  "createAuthentication");
        if (tokenId == null || tokenId.equals("")) {
            mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.create_token_error_validation)).getErrorMessage());
            authenticationCallback.onError(new XenditError(context.getString(R.string.create_token_error_validation)));
            return;
        }

        if (amount <= 0) {
            mLogger.log(Logger.Level.ERROR, new XenditError(context.getString(R.string.create_token_error_validation)).getErrorMessage());
            authenticationCallback.onError(new XenditError(context.getString(R.string.create_token_error_validation)));
            return;
        }

        String amountStr = Integer.toString(amount);

        _createAuthentication(tokenId, amountStr, new NetworkHandler<Authentication>().setResultListener(new ResultListener<Authentication>() {
            @Override
            public void onSuccess(Authentication authentication) {
                if (!authentication.getStatus().equalsIgnoreCase("VERIFIED")) {
                    registerBroadcastReceiver(authenticationCallback);
                    context.startActivity(XenditActivity.getLaunchIntent(context, authentication));
                } else {
                    authenticationCallback.onSuccess(authentication);
                }
                mLogger.log(Logger.Level.DEBUG,  "Successfully created auth!");
            }

            @Override
            public void onFailure(NetworkError error) {
                mLogger.log(Logger.Level.ERROR,  error.responseCode + " " + error.getMessage());
                authenticationCallback.onError(new XenditError(error));
            }
        }));
    }

    /**
     * @deprecated As of v.2.0.0, replaced by {@link #createAuthentication(String, int, AuthenticationCallback)}
     * cardCvn can be sent at creating charge
     */
    @Deprecated
    public void createAuthentication(final String tokenId, final String cardCvn, final String amount, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO,  "createAuthentication");
        if (tokenId == null || tokenId.isEmpty()) {
            mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.create_token_error_validation)).getErrorMessage());
            tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_validation)));
            return;
        }

        if (amount == null || Integer.parseInt(amount) <= 0) {

            mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.create_token_error_validation)).getErrorMessage());
            tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_validation)));
            return;
        }

        if (!isCvnValid(cardCvn)) {

            mLogger.log(Logger.Level.ERROR,  new XenditError(context.getString(R.string.create_token_error_card_cvn)).getErrorMessage());
            tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_card_cvn)));
            return;
        }

        _createAuthentication(tokenId, amount, new NetworkHandler<Authentication>().setResultListener(new ResultListener<Authentication>() {
            @Override
            public void onSuccess(Authentication authentication) {
                if (!authentication.getStatus().equalsIgnoreCase("VERIFIED")) {
                    registerBroadcastReceiver(tokenCallback);
                    context.startActivity(XenditActivity.getLaunchIntent(context, authentication));
                } else {
                    tokenCallback.onSuccess(new Token(authentication));
                }
                mLogger.log(Logger.Level.DEBUG,  "Successfully created auth!");
            }

            @Override
            public void onFailure(NetworkError error) {
                mLogger.log(Logger.Level.ERROR,  error.responseCode + " " + error.getMessage());
                tokenCallback.onError(new XenditError(error));
            }
        }));
    }

    private void tokenizeCreditCardRequest(final TokenConfiguration tokenConfiguration, final Card card, final String amount, final boolean shouldAuthenticate, final boolean isMultipleUse, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "tokenizeCreditCardRequest");
        tokenizeCreditCard(tokenConfiguration, card, new NetworkHandler<TokenCreditCard>().setResultListener(new ResultListener<TokenCreditCard>() {
            @Override
            public void onSuccess(TokenCreditCard tokenCreditCard) {
                _createCreditCardToken(card, tokenCreditCard.getToken(), amount, shouldAuthenticate, isMultipleUse, tokenCallback);
                mLogger.log(Logger.Level.DEBUG,  "Successfully tokenize credit card!");
            }

            @Override
            public void onFailure(NetworkError error) {
                mLogger.log(Logger.Level.ERROR,  error.responseCode + " " + error.getMessage());
                tokenCallback.onError(new XenditError(error));
            }
        }));
    }

    /**
     * @deprecated Not for public use.
     */
    @Deprecated
    public void createCreditCardToken(Card card, final String token, String amount, boolean isMultipleUse, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "createCreditCardToken");
        if (!isCvnValid(card.getCreditCardCVN())) {
            tokenCallback.onError(new XenditError(context.getString(R.string.create_token_error_card_cvn)));
            return;
        }

        _createToken(card, token, amount, true, isMultipleUse, new NetworkHandler<Authentication>().setResultListener(new ResultListener<Authentication>() {
            @Override
            public void onSuccess(Authentication authentication) {
                if (!authentication.getStatus().equalsIgnoreCase("VERIFIED")) {
                    registerBroadcastReceiver(tokenCallback);
                    context.startActivity(XenditActivity.getLaunchIntent(context, authentication));
                } else {
                    tokenCallback.onSuccess(new Token(authentication));
                }
                mLogger.log(Logger.Level.DEBUG,  "Successfully created token!");
            }

            @Override
            public void onFailure(NetworkError error) {
                mLogger.log(Logger.Level.ERROR, error.responseCode + " " + error.getMessage());
                tokenCallback.onError(new XenditError(error));
            }
        }));
    }

    private void _createCreditCardToken(Card card, final String token, String amount, boolean shouldAuthenticate, boolean isMultipleUse, final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "createCreditCardToken");
        _createToken(card, token, amount, shouldAuthenticate, isMultipleUse, new NetworkHandler<Authentication>().setResultListener(new ResultListener<Authentication>() {
            @Override
            public void onSuccess(Authentication authentication) {
                if (!authentication.getStatus().equalsIgnoreCase("VERIFIED")) {
                    registerBroadcastReceiver(tokenCallback);
                    context.startActivity(XenditActivity.getLaunchIntent(context, authentication));
                } else {
                    tokenCallback.onSuccess(new Token(authentication));
                }
                mLogger.log(Logger.Level.DEBUG,  "Successfully created token!");
            }

            @Override
            public void onFailure(NetworkError error) {
                mLogger.log(Logger.Level.ERROR,  error.responseCode + " " + error.getMessage());
                tokenCallback.onError(new XenditError(error));
            }
        }));
    }

    private void registerBroadcastReceiver(final AuthenticationCallback authenticationCallback) {
        mLogger.log(Logger.Level.INFO, "registerBroadcastReceiver");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                context.registerReceiver(new AuthenticationBroadcastReceiver(authenticationCallback), new IntentFilter(ACTION_KEY));
            }
        });
    }

    private void registerBroadcastReceiver(final TokenCallback tokenCallback) {
        mLogger.log(Logger.Level.INFO, "registerBroadcastReceiver");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                context.registerReceiver(new TokenBroadcastReceiver(tokenCallback), new IntentFilter(ACTION_KEY));
            }
        });
    }

    private void getTokenizationConfiguration(NetworkHandler<TokenConfiguration> handler) {
        mLogger.log(Logger.Level.INFO, "getTokenizationConfiguration");
        String encodedKey = encodeBase64(publishableKey + ":");
        String basicAuthCredentials = "Basic " + encodedKey;
        BaseRequest request = new BaseRequest<>(Request.Method.GET, GET_TOKEN_CONFIGURATION_URL, TokenConfiguration.class, new DefaultResponseHandler<>(handler));
        request.addHeader("Authorization", basicAuthCredentials.replace("\n", ""));
        sendRequest(request, handler);
    }

    private void tokenizeCreditCard(TokenConfiguration tokenConfig, Card card, NetworkHandler<TokenCreditCard> handler) {
        mLogger.log(Logger.Level.INFO, "tokenizeCreditCard");
        String baseUrl = getEnvironment() ? tokenConfig.getFlexProductionUrl() : tokenConfig.getFlexDevelopmentUrl();
        String flexUrl = baseUrl + TOKENIZE_CREDIT_CARD_URL + tokenConfig.getFlexApiKey();

        BaseRequest request = new BaseRequest<>(Request.Method.POST, flexUrl, TokenCreditCard.class, new DefaultResponseHandler<>(handler));

        JsonObject cardInfoJson = new JsonObject();
        cardInfoJson.addProperty("cardNumber", card.getCreditCardNumber());
        cardInfoJson.addProperty("cardExpirationMonth", card.getCardExpirationMonth());
        cardInfoJson.addProperty("cardExpirationYear", card.getCardExpirationYear());
        try {
            cardInfoJson.addProperty("cardType", CardValidator.getCardType(card.getCreditCardNumber()).getCardTypeKey());
        } catch (NullPointerException e) {
            mLogger.log(Logger.Level.ERROR, e.getMessage());
            e.printStackTrace();
            handler.handleError(new NetworkError(new VolleyError(e.getMessage(), e.getCause())));
        }

        request.addParam("keyId", tokenConfig.getTokenizationAuthKeyId());
        request.addJsonParam("cardInfo", cardInfoJson);
        sendRequest(request, handler);
    }

    private void _createToken(Card card, String token, String amount, boolean shouldAuthenticate, boolean isMultipleUse, NetworkHandler<Authentication> handler) {
        mLogger.log(Logger.Level.INFO, "_createToken");
        String encodedKey = encodeBase64(publishableKey + ":");
        String basicAuthCredentials = "Basic " + encodedKey;

        BaseRequest request = new BaseRequest<>(Request.Method.POST, CREATE_CREDIT_CARD_URL, Authentication.class, new DefaultResponseHandler<>(handler));
        request.addHeader("Authorization", basicAuthCredentials.replace("\n", ""));
        request.addParam("is_authentication_bundled", String.valueOf(!isMultipleUse));
        request.addParam("should_authenticate", String.valueOf(shouldAuthenticate));
        request.addParam("credit_card_token", token);
        request.addParam("card_cvn", card.getCreditCardCVN());

        if (!isMultipleUse) {
            request.addParam("amount", amount);
        }

        sendRequest(request, handler);
    }

    private void _createAuthentication(String tokenId, String amount, NetworkHandler<Authentication> handler) {
        mLogger.log(Logger.Level.INFO, "_createAuthentication");
        String encodedKey = encodeBase64(publishableKey + ":");
        String basicAuthCredentials = "Basic " + encodedKey;
        String requestUrl = CREATE_CREDIT_CARD_URL + "/" + tokenId + "/authentications";

        BaseRequest request = new BaseRequest<>(Request.Method.POST, requestUrl , Authentication.class, new DefaultResponseHandler<>(handler));
        request.addHeader("Authorization", basicAuthCredentials.replace("\n", ""));
        request.addParam("amount", amount);
        sendRequest(request, handler);
    }

    private String encodeBase64(String key) {
        mLogger.log(Logger.Level.INFO, "encodeBase64");
        try {
            byte[] keyData = key.getBytes("UTF-8");
            return Base64.encodeToString(keyData, Base64.DEFAULT);
        } catch (UnsupportedEncodingException e) {
            mLogger.log(Logger.Level.ERROR, e.getCause() + " " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private void sendRequest(BaseRequest request, NetworkHandler<?> handler) {
        mLogger.log(Logger.Level.INFO, "sendRequest");
        if (isConnectionAvailable()) {
            mLogger.log(Logger.Level.DEBUG, "Connected!");
            requestQueue.add(request);
        } else if (handler != null) {
            mLogger.log(Logger.Level.ERROR, new ConnectionError().getMessage());
            handler.handleError(new ConnectionError());
        }
    }

    private boolean isConnectionAvailable() {
        mLogger.log(Logger.Level.INFO, "isConnectionAvailable");
        if (PermissionUtils.hasPermission(context, Manifest.permission.ACCESS_NETWORK_STATE)) {
            @SuppressLint("MissingPermission") NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            mLogger.log(Logger.Level.DEBUG, "Had access network state");
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        } else {
            mLogger.log(Logger.Level.ERROR, context.getString(R.string.not_granted_access_network_state));
            return false;
        }

    }

    private boolean getEnvironment() {
        mLogger.log(Logger.Level.INFO, "getEnvironment");
        String publishKey = publishableKey.toUpperCase();
        return publishKey.contains("PRODUCTION");
    }



    /**
     * Method that will filter that only exception that are generated with our library
     * are sent to sentry
     */
    private void shouldSendException() {
        SentryClient client = Sentry.getStoredClient();

        client.addShouldSendEventCallback(new ShouldSendEventCallback() {
            @Override
            public boolean shouldSend(Event event) {
                // decide whether to send the event
                for (Map.Entry<String, SentryInterface> interfaceEntry : event.getSentryInterfaces().entrySet()) {
                    if (interfaceEntry.getValue() instanceof ExceptionInterface) {
                        ExceptionInterface i = (ExceptionInterface) interfaceEntry.getValue();
                        for (SentryException sentryException : i.getExceptions()) {
                            for (SentryStackTraceElement element : sentryException.getStackTraceInterface().getStackTrace()) {
                                if (element.getModule().contains("com.xendit")) {
                                    return true;
                                }
                            }
                        }
                    }
                }

                // send event
                return true;
            }
        });
    }
}