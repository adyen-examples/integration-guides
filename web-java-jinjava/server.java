package controller;

import com.adyen.model.checkout.PaymentsDetailsRequest;
import com.adyen.model.checkout.PaymentsRequest;
import com.adyen.model.checkout.PaymentsResponse;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.common.net.MediaType;
import org.apache.http.NameValuePair;
import spark.QueryParamsMap;
import view.RenderUtil;

import java.io.*;
import java.util.*;


import model.PaymentMethods;
import model.Payments;
import model.PaymentsDetails;

import static spark.Spark.*;
import spark.Response;


public class Main {

	private static final File FAVICON_PATH = new File("src/main/resources/static/img/favicon.ico");
	private static final String configFile = "config.properties";

	public static String merchantAccount = "";
	public static String apiKey = "";
	public static String clientKey = "";

	public static void main(String[] args) {
		port(8080);
		staticFiles.location("/static");
		readConfigFile();

        Client client = new Client(Main.apiKey, Environment.TEST);
        Checkout checkout = new Checkout(client);

		// Routes
		get("/", (req, res) -> {
			Map<String, Object> context = new HashMap<>();
			return RenderUtil.render(context, "templates/home.html");
		});

		get("/cart/:integration", (req, res) -> {
			String integrationType = req.params(":integration");

			Map<String, Object> context = new HashMap<>();
			context.put("integrationType", "/checkout/" + integrationType);

			return RenderUtil.render(context, "templates/cart.html");
		});

		get("/checkout/:integration", (req, res) -> {
			String integrationType = req.params(":integration");

			Map<String, Object> context = new HashMap<>();
			context.put("paymentMethods", PaymentMethods.getPaymentMethods(integrationType));
			context.put("clientKey", clientKey);
			context.put("integrationType", integrationType);

			return RenderUtil.render(context, "templates/component.html");
		});

		post("/api/getPaymentMethods", (req, res) -> {
            String paymentMethods;

            PaymentMethodsRequest paymentMethodsRequest = new PaymentMethodsRequest();
            paymentMethodsRequest.setMerchantAccount(Main.merchantAccount);

            Amount amount = new Amount();
            if (type.equals("dotpay")) {
                amount.setCurrency("PLN");
            } else {
                amount.setCurrency("EUR");
            }
            amount.setValue(1000L);
            paymentMethodsRequest.setAmount(amount);
            paymentMethodsRequest.setChannel(PaymentMethodsRequest.ChannelEnum.WEB);
            System.out.println("/paymentMethods context:\n" + paymentMethodsRequest.toString());

            try {
                PaymentMethodsResponse response = checkout.paymentMethods(paymentMethodsRequest);
                Gson gson = new GsonBuilder().create();
                paymentMethods = gson.toJson(response);
                System.out.println("/paymentMethods response:\n" + paymentMethods);
            } catch (ApiException | IOException e) {
                return e.toString();
            }

			return paymentMethods;
		});

		post("/api/initiatePayment", (req, res) -> {
			System.out.println("Request received from client:\n" + req.body());
			PaymentsRequest paymentsRequest = FrontendParser.parsePayment(req.body());

            String type = paymentsRequest.getPaymentMethod().getType();

            setAmount(paymentsRequest, type);
            paymentsRequest.setChannel(PaymentsRequest.ChannelEnum.WEB);
            paymentsRequest.setMerchantAccount(Main.merchantAccount);
            paymentsRequest.setReturnUrl("http://localhost:8080/api/handleShopperRedirect");

            paymentsRequest.setReference("Java Integration Test Reference");
            paymentsRequest.setShopperReference("Java Checkout Shopper");

            paymentsRequest.setCountryCode("NL");

            if (type.equals("alipay")) {
                paymentsRequest.setCountryCode("CN");

            } else if (type.contains("klarna")) {
                paymentsRequest.setShopperEmail("myEmail@adyen.com");
                paymentsRequest.setShopperLocale("en_US");

                addLineItems(paymentsRequest);

            } else if (type.equals("directEbanking") || type.equals("giropay")) {
                paymentsRequest.countryCode("DE");

            } else if (type.equals("dotpay")) {
                paymentsRequest.countryCode("PL");
                paymentsRequest.getAmount().setCurrency("PLN");

            } else if (type.equals("scheme")) {
                paymentsRequest.setOrigin("http://localhost:8080");
                paymentsRequest.putAdditionalDataItem("allow3DS2", "true");

            } else if (type.equals("ach") || type.equals("paypal")) {
                paymentsRequest.countryCode("US");
            }

            System.out.println("/payments request:\n" + paymentsRequest.toString());

            try {
                PaymentsResponse response = checkout.payments(paymentsRequest);
                PaymentsResponse formattedResponse = FrontendParser.formatResponseForFrontend(response);

                GsonBuilder builder = new GsonBuilder();
                Gson gson = builder.create();
                String paymentsResponse = gson.toJson(formattedResponse);
                System.out.println("/payments response:\n" + paymentsResponse);
                return paymentsResponse;
            } catch (ApiException | IOException e) {
                return e.toString();
            }
		});

		post("/api/submitAdditionalDetails", (req, res) -> {
			PaymentsDetailsRequest paymentsDetailsRequest = FrontendParser.parseDetails(req.body());
            System.out.println("/paymentsDetails request:" + paymentsDetailsRequest.toString());
            PaymentsResponse paymentsDetailsResponse = null;
            try {
                paymentsDetailsResponse = checkout.paymentsDetails(paymentsDetailsRequest);

            } catch (ApiException | IOException e) {
                e.printStackTrace();
            } finally {
                if (paymentsDetailsResponse != null) {
                    System.out.println("paymentsDetails response:\n" + paymentsDetailsResponse.toString());
                }
            }

		    Gson gson = new GsonBuilder().create();
		    return gson.toJson(paymentsDetailsResponse);    
		});

		get("/api/handleShopperRedirect", (req, res) -> {
			System.out.println("GET redirect handler");

			QueryParamsMap queryMap = req.queryMap();
			String key = "";
			String value = "";

			if (queryMap.hasKey("redirectResult")) {
				key =  "redirectResult";
				value = queryMap.value("redirectResult");

			} else if (queryMap.hasKey("payload")) {
				key = "payload";
				value = queryMap.value("payload");
			}

			Map<String, Object> context = new HashMap<>();
			String valuesArray = "{\n" +
					"\"" + key + "\": \"" + value + "\"" +
					"}";
			context.put("valuesArray", valuesArray);

			return RenderUtil.render(context, "templates/fetch-payment-data.html"); // Get paymentData from localStorage
		});

		post("/api/handleShopperRedirect", (req, res) -> {
			System.out.println("POST redirect handler");

			// Triggers when POST contains query params. Triggers on call back from issuer after 3DS2 challenge w/ MD & PaRes
			if (!req.body().contains("paymentData")) {
				List<NameValuePair> params = FrontendParser.parseQueryParams(req.body());
				String md = params.get(0).getValue();
				String paRes = params.get(1).getValue();

				Map<String, Object> context = new HashMap<>();
				String valuesArray = "{\n" +
						"\"MD\": \"" + md + "\",\n" +
						"\"PaRes\": \"" + paRes + "\"\n" +
						"}";
				context.put("valuesArray", valuesArray);

				return RenderUtil.render(context, "templates/fetch-payment-data.html"); // Get paymentData from localStorage

			} else {
				PaymentsDetailsRequest pdr = FrontendParser.parseDetails(req.body());

				PaymentsResponse paymentResult = PaymentsDetails.getPaymentsDetailsObject(pdr);
				PaymentsResponse.ResultCodeEnum result = paymentResult.getResultCode();

				switch (result) {
					case AUTHORISED:
						res.redirect("/success");
						break;
					case RECEIVED: case PENDING:
						res.redirect("/pending");
						break;
					default:
						res.redirect("/failed");
				}
				return res;
			}
		});

		get("/success", (req, res) -> {
			Map<String, Object> context = new HashMap<>();
			return RenderUtil.render(context, "templates/checkout-success.html");
		});

		get("/failed", (req, res) -> {
			Map<String, Object> context = new HashMap<>();
			return RenderUtil.render(context, "templates/checkout-failed.html");
		});

		get("/pending", (req, res) -> {
			Map<String, Object> context = new HashMap<>();
			return RenderUtil.render(context, "templates/checkout-success.html");
		});

		get("/error", (req, res) -> {
			Map<String, Object> context = new HashMap<>();
			return RenderUtil.render(context, "templates/checkout-failed.html");
		});

		get("/favicon.ico", (req, res) -> {
			return getFavicon(res);
		});
	}

	private static Object getFavicon(Response res) {
		try {
			InputStream in = null;
			OutputStream out;
			try {
				in = new BufferedInputStream(new FileInputStream(FAVICON_PATH));
				out = new BufferedOutputStream(res.raw().getOutputStream());
				res.raw().setContentType(MediaType.ICO.toString());
				ByteStreams.copy(in, out);
				out.flush();
				return "";
			} finally {
				Closeables.close(in, true);
			}
		} catch (FileNotFoundException ex) {
			res.status(404);
			return ex.getMessage();
		} catch (IOException ex) {
			res.status(500);
			return ex.getMessage();
		}
	}

	private static void readConfigFile() {

		Properties prop = new Properties();

		try {
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(configFile));
			prop.load(in);
			in.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		merchantAccount = prop.getProperty("merchantAccount");
		apiKey = prop.getProperty("apiKey");
		clientKey = prop.getProperty("clientKey");
	}
}