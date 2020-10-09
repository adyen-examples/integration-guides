# Drop-in tutorial: Java

This tutorial explains the server- and client-side steps for integrating [Drop-in](/checkout/drop-in-web), our complete UI solution for accepting payments on your website. Integrating Drop-in is the quickest way to offer multiple payment methods, without having to build each payment method individually.

After completing this tutorial, you'll have a base integration that supports all [payment flows](/payment-methods#payment-flow), and can be quickly extended to support most payment methods.

! To learn more about our available integration options, refer to [Online payments](/checkout#comparing-integration-options).

## How it works

When your shopper visits your checkout page, your server makes an API call to Adyen to get a list of available payment methods for the shopper. The shopper then selects a payment method from that list, and enters their payment details in the input fields rendered by Drop-in.

When the shopper selects the **Pay** button, your server uses the data returned by the client to submit a payment request to Adyen. If the response requires additional action from the shopper, Drop-in can handle these additional actions. In such cases, your server will make another request to Adyen to complete or verify the payment. When no additional actions are required, the payment result can be presented to the shopper.

## Overview of the build process

After following this tutorial, you'll have a complete checkout solution including both server- and client-side implementations.

#### Server implementation

You'll begin by exposing a few endpoints on your server to handle all shopper interactions at checkout. The names of your server endpoints in this tutorial match those in Adyen's [example integrations](https://github.com/adyen-examples/). You can name these endpoints however you like.

| HTTP method   | Your server endpoint                              | Role                                                                                                                                                                                                                                                                                                                                                                              | Related Adyen API endpoint                                         |
| ------------- | ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `GET`         | [`/getPaymentMethods`](#get-payment-methods)      | Gets the available payment methods.                                                                                                                                                                                                                                                                                                                                               | [plugin:content-inject](/reuse/endpoints/paymentmethods-endpoint)  |
| `POST`        | [`/initiatePayment`](#initiate-payment)           | Sends payment parameters together with the input details collected from the shopper.                                                                                                                                                                                                                                                                                              | [plugin:content-inject](/reuse/endpoints/payments-endpoint)        |
| `POST`, `GET` | [`/handleShopperRedirect`](#handle-redirect)      | Sends payment details during a page redirect and determines the outcome for the shopper. <br> <br> Used for all payment methods that require a redirect, for example [iDEAL](/payment-methods/ideal/web-drop-in) or [Klarna](/payment-methods/klarna/web-drop-in).                                                                                                                | [plugin:content-inject](/reuse/endpoints/paymentsdetails-endpoint) |
| `POST`        | [`/submitAdditionalDetails`](#additional-details) | Sends payment details when a shopper is required to perform additional actions without leaving your website. <br> <br> Examples: [Native 3D Secure 2 authentication](/checkout/3d-secure/native-3ds2/web-drop-in), native QR ([WeChat Pay](/payment-methods/wechat-pay/wechat-pay-desktop-qr-payments/web-drop-in)), and overlays ([PayPal](/payment-methods/paypal/web-drop-in)) | [plugin:content-inject](/reuse/endpoints/paymentsdetails-endpoint) |

These endpoints are custom routes (URI patterns) that interact with the Adyen API at specific points in the checkout process, and are accessed by your client through `fetch()` requests. With the exception of `/handleShopperRedirect`, each endpoint returns a JSON response data back to the client.

#### Client implementation

After implementing the back end, you'll implement the front end with a configurable, ready-to-use Drop-in UI component.

You'll first [add the necessary scripts and styles](#scripts) to your checkout page. Then, you'll [create `fetch()` requests](#connect-to-your-server) and [event handlers](#create-the-event-handler) to facilitate the app's client-server interactions. Finally, you'll [configure and instantiate the instance of Drop-in](#configure-drop-in) mounted to your checkout page. You'll then be ready to start testing transactions with hundreds of payment methods from around the world.

## Before you begin

This tutorial shows both server- and client-side integration steps for Drop-in. To be well prepared, you should have experience working with Node.js, Express, and a template system (such as Handlebars).

Before you begin your integration, make sure you have the following tools and credentials:

| Item                                                                                                  | Description                                                                                           |
| ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| [Adyen test account](/checkout/get-started#step-1-sign-up-for-a-test-account)                         | This gives you access to the test [plugin:content-inject](/reuse/url-library/customer-area-test-url). |
| [API key](/checkout/get-started#api-key)                                                              | Private API key used to authenticate API calls to the Adyen payments platform.                        |
| [Client key](/user-management/client-side-authentication)                                             | Public key used to authenticate requests from your web environment.                                   |
| [Adyen server-side library for Node.js](/checkout/get-started?tab=node_js_6#step-4-install-a-library) | API library that allows you to easily work with Adyen's APIs.                                         |

## Build the server

You'll first implement the back end to make sure your server is ready for the checkout process.

#### Install dependencies

The integration on this page uses Node.js and Express, with Handlebars templating. For a smoother integration experience, the examples on this page use the following libraries as well:

| npm package                                                            | Description                                                                 |
| ---------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| [express-handlebars](https://www.npmjs.com/package/express-handlebars) | Handlebars template (view) engine for Express.                              |
| [cookie-parser](https://www.npmjs.com/package/cookie-parser)           | Middleware to help parse cookie headers, as well as to get and set cookies. |

In the root of your project, install the dependencies:

```bash
npm install --save express @adyen/api-library express-handlebars cookie-parser
```

#### Configure the server

After installing the dependencies, import the modules and configure the Adyen client in your server file (we use **index.js** in this example). Provide your API key as a string in the boilerplate code.

```js
const express = require("express");
const path = require("path");
const hbs = require("express-handlebars");
const cookieParser = require("cookie-parser");
const { Client, Config, CheckoutAPI } = require("@adyen/api-library");
const app = express();

// Parse JSON bodies
app.use(express.json());
// Parse URL-encoded bodies
app.use(express.urlencoded({ extended: true }));
// Parse cookie bodies, and allow setting/getting cookies
app.use(cookieParser());

app.use(express.static(path.join(__dirname, "/public")));

// Adyen Node.js API library boilerplate (configuration, etc.)
const config = new Config();
config.apiKey = "YOUR_API_KEY_HERE";
const client = new Client({ config });
client.setEnvironment("TEST");
const checkout = new CheckoutAPI(client);

// Use Handlebars as the view engine
app.set("view engine", "handlebars");

// Specify where to find view files
app.engine(
  "handlebars",
  hbs({
    defaultLayout: "main",
    layoutsDir: __dirname + "/views/layouts",
    partialsDir: __dirname + "/views/partials",
  })
);
```

Next, you'll expose a few endpoints on your server to support different points in the checkout process.

#### Get available payment methods {#get-payment-methods}

The first point in the checkout process: the shopper is ready to pay and looks to select a payment method.

To get the available payment methods for the shopper, you'll expose an endpoint on your server by creating a GET route to `/getPaymentMethods`. When this endpoint is accessed, the instance of checkout will make a call to Adyen's [plugin:content-inject](/reuse/endpoints/paymentmethods-endpoint) endpoint.

Pass in your `merchantAccount` and `channel`:**Web**. You can also specify `amount` and `countryCode` parameters&mdash; we'll use these to tailor the list of available payment methods to your shopper.

Next, you'll pass the entire JSON-stringified response from your server back to your client. In the example below, you'll pass it back to your checkout page, shown as the rendered **payment** template.

```js
app.get("getPaymentMethods", (req, res) => {
  checkout
    .paymentMethods({
      channel: "Web",
      merchantAccount: config.merchantAccount,
    })
    .then((response) => {
      // Adyen API response is passed to the client
      res.render("payment", {
        response: JSON.stringify(response),
      });
    });
});
```

An array of available payment methods is returned in the response, each with a `name` and `type`. Drop-in uses the response to show the available payment methods to the shopper.

```js
{
  "paymentMethods":[
    {
      "details":[...],
      "name":"Credit Card",
      "type":"scheme"
      // ...
    },
    // ...
  ]
}
```

! We'll revisit this response object later on the client side when we [configure Drop-in](#configure-drop-in).

#### Get available payment methods {#get-payment-methods}

The first point in the checkout process: the shopper is ready to pay and looks to select a payment method.

To get the available payment methods for the shopper, you'll expose an endpoint on your server by creating a GET route to `/getPaymentMethods`. When this endpoint is accessed, the instance of checkout will make a call to Adyen's [plugin:content-inject](/reuse/endpoints/paymentmethods-endpoint) endpoint.

Pass in your `merchantAccount` and `channel`:**Web**. You can also specify `amount` and `countryCode` parameters&mdash; we'll use these to tailor the list of available payment methods to your shopper.

Next, you'll pass the entire JSON-stringified response from your server back to your client. In the example below, you'll pass it back to your checkout page, shown as the rendered **payment** template.

```js
app.get("getPaymentMethods", (req, res) => {
  checkout
    .paymentMethods({
      channel: "Web",
      merchantAccount: config.merchantAccount,
    })
    .then((response) => {
      // Adyen API response is passed to the client
      res.render("payment", {
        response: JSON.stringify(response),
      });
    });
});
```

An array of available payment methods is returned in the response, each with a `name` and `type`. Drop-in uses the response to show the available payment methods to the shopper.

```js
{
  "paymentMethods":[
    {
      "details":[...],
      "name":"Credit Card",
      "type":"scheme"
      // ...
    },
    // ...
  ]
}
```

! We'll revisit this response object later on the client side when we [configure Drop-in](#configure-drop-in).

#### Initiate a payment {#initiate-payment}

At this point in the checkout process, the shopper selects a payment method, enters their payment details, and selects the **Pay** button.

To initiate the payment, you'll expose an endpoint on your server by creating a POST route to `/initiatePayment`. When this endpoint is accessed, a call will be made to Adyen's [plugin:content-inject](/reuse/endpoints/payments-endpoint) endpoint.

In our example, the shopper selects to pay with their card. Specify the `amount`, `reference`, `merchantAccount`, `paymentMethod`, `channel`, `returnUrl`, `browserInfo`, and `shopperIP` in the request body. Note that the values of certain parameters, such as `browserInfo` and `paymentMethod`, represent data passed from the client when performing the `fetch()` request.

A sample request for a credit card payment (with [3D Secure redirect authentication](/checkout/3d-secure)) of **10** EUR:

```js
// Note the jsonParser instance to help read the bodies of incoming JSON data
app.post("/initiatePayment", (req, res) => {
  checkout
    .payments({
      amount: { currency: "EUR", value: 1000 },
      reference: "12345",
      merchantAccount: config.merchantAccount,
      channel: "Web",
      returnUrl: "http://localhost:8080/handleShopperRedirect",
      browserInfo: req.body.browserInfo,
      paymentMethod: req.body.paymentMethod,
    })
    .then((response) => {
      const { resultCode, action } = response;

      if (action.paymentData) {
        res.cookie("paymentData", action.paymentData);
      }

      res.json({ resultCode, action });
    });
});
```

If the response contains an `action` object, this means that additional client-side actions are required to complete the payment. As shown above, you'll pass this `action` object (not the entire response) from the server to the client, where it will be handled by Drop-in. You'll also temporarily save `paymentData` (for example, in a database, or in a cookie as shown in this example). You'll need to include the `paymentData` in your [next API request](#handle-redirect) to check the payment result after a page redirection.

```js
{
   "resultCode":"RedirectShopper",
   "action":{
      "data":{
         "MD":"OEVudmZVMUlkWjd0MDNwUWs2bmhSdz09...",
         "PaReq":"eNpVUttygjAQ/RXbDyAXBYRZ00HpTH3wUosPfe...",
         "TermUrl":"https://your-company.com/checkout?shopperOrder=12xy.."
      },
      "method":"POST",
      "paymentData":"Ab02b4c0!BQABAgCJN1wRZuGJmq8dMncmypvknj9s7l5Tj...",
      "paymentMethodType":"scheme",
      "type":"redirect",
      "url":"https://test.adyen.com/hpp/3d/validate.shtml"
   },
   "details":[
      {
         "key":"MD",
         "type":"text"
      },
      {
         "key":"PaRes",
         "type":"text"
      }
   ],
   // ...
}
```

! We'll revisit this `action` object later on the client side when we [configure Drop-in to handle the action](#create-the-event-handler).

If there's no `action` object in the response, no additional actions are needed to complete the payment. This means that the transaction has reached a final status (for example, in a 3D Secure scenario, the transaction was either exempted or out-of-scope for 3D Secure authentication). Pass the `resultCode` included in the response to your client &mdash; you'll use this to present the payment result to your shopper.

#### Handle the redirect {#handle-redirect}

At this point in the checkout process, the shopper has been redirected to another site to complete the payment, and back to your `returnUrl` using either an HTTP `POST` or `GET`. This redirection is a result of Drop-in performing the `action.type`: **redirect** specified in the [plugin:content-inject](/reuse/endpoints/payments-endpoint) response.

After the shopper is redirected back, you need to verify or complete the payment using data from the redirected page. You'll do that by exposing two more endpoints on your server, representing a `GET` and `POST` route to `/handleShopperRedirect`.

When either endpoint is accessed, a call will be made to Adyen's [plugin:content-inject](/reuse/endpoints/paymentsdetails-endpoint) endpoint. In the request, specify:

- `paymentData`: the value from the [plugin:content-inject](/reuse/endpoints/payments-endpoint) response.
- `details`: data returned from redirected page.

```js
app.get("/handleShopperRedirect", (req, res) => {
  // Create the payload for submitting payment details
  let payload = {};
  payload["details"] = req.query;
  payload["paymentData"] = req.cookies["paymentData"];

  checkout.paymentsDetails(payload).then((response) => {
    // Clear paymentData cookie after initially setting it in the /initiatePayment endpoint
    res.clearCookie("paymentData");
    // Conditionally handle different result codes for the shopper
    switch (response.resultCode) {
      case "Authorised":
        res.redirect("/success");
        break;
      case "Pending":
        res.redirect("/pending");
        break;
      case "Refused":
        res.redirect("/failed");
        break;
      default:
        res.redirect("/error");
        break;
    }
  });
});
app.post("/handleShopperRedirect", (req, res) => {
  let payload = {};
  payload["details"] = req.body;
  payload["paymentData"] = req.cookies["paymentData"];

  checkout.paymentsDetails(payload).then((response) => {
    res.clearCookie("paymentData");
    switch (response.resultCode) {
      case "Authorised":
        res.redirect("/success");
        break;
      case "Pending":
        res.redirect("/pending");
        break;
      case "Refused":
        res.redirect("/failed");
        break;
      default:
        res.redirect("/error");
        break;
    }
  });
});
```

You'll receive a response with a `pspReference` and a `resultCode`, the latter of which can be used to [present the payment result](#handle-result-codes) to the shopper.

**Successful response**

```js
{
 "pspReference": "88154795347618C",
 "resultCode": "Authorised"
}
```

If you receive another `action` object in the response, pass this back to the client again.

! We'll configure the Drop-in to [handle additional actions](#create-the-event-handler) in case you receive more action objects in the response.

#### Handle other additional details {#additional-details}

For some payment flows, the shopper needs to perform additional steps without leaving your website. For example, this can mean scanning a QR code, or completing a challenge in case of [native 3D Secure 2 authentication](/checkout/3d-secure#authentication-flows).

To handle submitting these additional details, you'll expose another endpoint on your server by creating a `POST` route to `/submitAdditionalDetails`. Similar to `/handleShopperRedirect` above, specify `details` and `paymentData` parameters in the call to Adyen's [plugin:content-inject](/reuse/endpoints/paymentsdetails-endpoint) endpoint. Note that this time, the shopper stays in your website so there is no view redirect logic in the server. Instead, any additional `action` objects are passed back to the client. The result code is also passed to the client, and this will be used to present the payment result to the shopper.

#### Create the event handler

During the checkout process, we need to handle certain events such as when the shopper selects the **Pay** button, or when additional information is required to complete the payment.

We can wrap the above client-server functions nicely in a single, reusable function. We'll call this function `handleSubmission()`. The function calls the server at the provided endpoint and passes the payment data.

```js
function handleSubmission(state, component, url) {
  callServer(url, state.data)
    .then((res) => handleServerResponse(res, component))
    .catch((error) => {
      throw Error(error);
    });
}
```

After the request is resolved, Drop-in handles the data returned in the Adyen API response.

#### Configure Drop-in

After building the client-server interaction, we'll now configure and instantiate Drop-in.

Create a `configuration` object with the following parameters:

| Parameter name                |      Required      | Description                                                                                                                                                                                                      |
| ----------------------------- | :----------------: | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `paymentMethodsResponse`      | :white_check_mark: | The response from your `/paymentMethods` request passed to the client.                                                                                                                                           |
| `clientKey`                   | :white_check_mark: | Public key used to authenticate requests from your web environment. <br> For more information, refer to [client side authentication](/user-management/client-side-authentication).                               |
| `locale`                      | :white_check_mark: | The shopper's locale. This is used to set the language rendered in the UI. For a list of supported locales, see [Localization](/checkout/components-web/localization-components).                                |
| `environment`                 | :white_check_mark: | Use **test**. When you're ready to accept live payments, change the value to one of our [live environments](/checkout/drop-in-web#testing-your-integration).                                                     |
| `onSubmit`                    | :white_check_mark: | Assign the reusable event handler`handleSubmission` with your `/initiatePayment` endpoint. The `onSubmit` event is called when the shopper selects the **Pay** button.                                           |
| `onAdditionalDetails`         | :white_check_mark: | Assign the reusable event handler `handleSubmission` with your `/submitAdditionalDetails` endpoint. The `onAdditionalDetails` event is called if no final state was reached, or additional details are required. |
| `paymentMethodsConfiguration` |                    | Required or optional configuration for specific payment methods. For more information about payment method-specific configurations, refer to our [payment method guides](/payment-methods).                      |

For our cards example, we'll include optional configuration - such as `showPayButton`, `hasHolderName`, `holderNameRequired`, `name`, and `amount`. For a full list of configurable fields, refer to [optional Card configuration](/payment-methods/cards/web-drop-in#drop-in-configuration).

```js
const configuration = {
  paymentMethodsResponse: { response },
  clientKey: "YOUR_CLIENT_KEY",
  locale: "en_US",
  environment: "test",
  onSubmit: (state, component) => {
    handleSubmission(state, component, "/initiatePayment";)
  },
  onAdditionalDetails: (state, component) => {
    handleSubmission(state, component, "/submitAdditionalDetails";)
  },
  paymentMethodsConfiguration: {
    card: {
      showPayButton: true,
      hasHolderName: true,
      holderNameRequired: true,
      name: "Credit or debit card",
      amount: {
        value: 1000,
        currency: "EUR"
      }
    }
  }
};
```

#### Instantiate and mount Drop-in

After all the configuration are set, you are ready to instantiate your Drop-in. Create an instance of `AdyenCheckout` with your newly-created configuration object. You'll use the returned value to create and mount the instance of your UI Drop-in:

```js
const checkout = new AdyenCheckout(configuration);
const integration = checkout.create("dropin").mount("#dropin-container");
```
