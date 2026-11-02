module Api exposing
    ( AccountActivity
    , AccountOverview
    , ApiError
    , BalanceChange
    , BillPaymentAccepted
    , BillPaymentInquiry
    , BillPaymentProcess
    , BillRequest
    , Effect(..)
    , PaymentStatus(..)
    , TransferResult
    , confirmBillPayment
    , deposit
    , getAccountOverview
    , getBillPayment
    , inquireBill
    , isProcessing
    , openAccount
    , operationLabel
    , paymentStatusLabel
    , transfer
    , withdraw
    )

{-| Banking API client. Requests go to /api, which the Vite dev server and
nginx proxy to the Scalatra backend.
-}

import Http
import Json.Decode as Decode exposing (Decoder)
import Json.Encode as Encode
import Url.Builder



-- TYPES


type alias ApiError =
    { code : String
    , message : String
    , status : Int
    }


type Effect
    = Increase
    | Decrease


type alias AccountActivity =
    { transactionId : String
    , operation : String
    , effect : Effect
    , amount : Float
    , balanceAfter : Float
    , occurredAt : String
    , status : String
    , counterpartyAccountId : Maybe String
    }


type alias AccountOverview =
    { accountId : String
    , currency : String
    , balance : Float
    , activities : List AccountActivity
    }


type alias BalanceChange =
    { accountId : String
    , balance : Float
    }


type alias TransferResult =
    { transferId : String
    , destinationAccountId : String
    , amount : Float
    , sourceBalance : Float
    }


type alias BillRequest =
    { billerCode : String
    , referenceCode1 : String
    , referenceCode2 : String
    }


type alias BillPaymentInquiry =
    { inquiryId : String
    , billerCode : String
    , referenceCode1 : String
    , referenceCode2 : String
    , currentDebt : Float
    , expiresAt : String
    }


type alias BillPaymentAccepted =
    { paymentId : String
    }


type PaymentStatus
    = AwaitingAccountCharge
    | Settling
    | Reversing
    | Completed
    | Failed
    | ManualReview


type alias BillPaymentProcess =
    { paymentId : String
    , billerCode : String
    , amount : Float
    , status : PaymentStatus
    , resultingBalance : Maybe Float
    , billerReceiptCode : Maybe String
    , paidAt : Maybe String
    , failureMessage : Maybe String
    }



-- REQUESTS


openAccount : String -> (Result ApiError () -> msg) -> Cmd msg
openAccount accountId toMsg =
    request "PUT" [ accountId ] (Encode.object []) toMsg (Decode.succeed ())


getAccountOverview : String -> (Result ApiError AccountOverview -> msg) -> Cmd msg
getAccountOverview accountId toMsg =
    request "GET" [ accountId, "overview" ] Encode.null toMsg overviewDecoder


deposit : String -> Float -> (Result ApiError BalanceChange -> msg) -> Cmd msg
deposit accountId amount toMsg =
    request "POST" [ accountId, "deposits" ] (amountBody amount) toMsg balanceChangeDecoder


withdraw : String -> Float -> (Result ApiError BalanceChange -> msg) -> Cmd msg
withdraw accountId amount toMsg =
    request "POST" [ accountId, "withdrawals" ] (amountBody amount) toMsg balanceChangeDecoder


transfer : String -> String -> Float -> (Result ApiError TransferResult -> msg) -> Cmd msg
transfer sourceAccountId destinationAccountId amount toMsg =
    request "POST"
        [ sourceAccountId, "transfers" ]
        (Encode.object
            [ ( "destinationAccountId", Encode.string destinationAccountId )
            , ( "amount", Encode.float amount )
            ]
        )
        toMsg
        (Decode.map4 TransferResult
            (Decode.field "transferId" Decode.string)
            (Decode.field "destinationAccountId" Decode.string)
            (Decode.field "amount" Decode.float)
            (Decode.field "sourceBalance" Decode.float)
        )


inquireBill : String -> BillRequest -> (Result ApiError BillPaymentInquiry -> msg) -> Cmd msg
inquireBill accountId bill toMsg =
    request "POST"
        [ accountId, "bill-payments", "inquiries" ]
        (Encode.object
            [ ( "billerCode", Encode.string bill.billerCode )
            , ( "referenceCode1", Encode.string bill.referenceCode1 )
            , ( "referenceCode2", Encode.string bill.referenceCode2 )
            ]
        )
        toMsg
        (Decode.map6 BillPaymentInquiry
            (Decode.field "inquiryId" Decode.string)
            (Decode.field "billerCode" Decode.string)
            (Decode.field "referenceCode1" Decode.string)
            (Decode.field "referenceCode2" Decode.string)
            (Decode.field "currentDebt" Decode.float)
            (Decode.field "expiresAt" Decode.string)
        )


confirmBillPayment : String -> String -> (Result ApiError BillPaymentAccepted -> msg) -> Cmd msg
confirmBillPayment accountId inquiryId toMsg =
    request "POST"
        [ accountId, "bill-payments", inquiryId, "confirm" ]
        (Encode.object [])
        toMsg
        (Decode.map BillPaymentAccepted (Decode.field "paymentId" Decode.string))


getBillPayment : String -> String -> (Result ApiError BillPaymentProcess -> msg) -> Cmd msg
getBillPayment accountId paymentId toMsg =
    request "GET" [ accountId, "bill-payments", paymentId ] Encode.null toMsg billPaymentDecoder


amountBody : Float -> Encode.Value
amountBody amount =
    Encode.object [ ( "amount", Encode.float amount ) ]


{-| `Encode.null` stands for "no body"; only GET requests use it. -}
request : String -> List String -> Encode.Value -> (Result ApiError a -> msg) -> Decoder a -> Cmd msg
request method pathSegments body toMsg decoder =
    Http.request
        { method = method
        , headers = []
        , url = Url.Builder.absolute ("api" :: "accounts" :: pathSegments) []
        , body =
            if method == "GET" then
                Http.emptyBody

            else
                Http.jsonBody body
        , expect = expectApi toMsg decoder
        , timeout = Nothing
        , tracker = Nothing
        }


{-| Like `Http.expectJson`, but keeps the API's `{error, message}` body on failure. -}
expectApi : (Result ApiError a -> msg) -> Decoder a -> Http.Expect msg
expectApi toMsg decoder =
    Http.expectStringResponse toMsg <|
        \response ->
            case response of
                Http.GoodStatus_ _ body ->
                    Decode.decodeString decoder body
                        |> Result.mapError (\_ -> ApiError "unexpected_error" "An unexpected error occurred." 0)

                Http.BadStatus_ metadata body ->
                    Err
                        (Decode.decodeString errorBodyDecoder body
                            |> Result.map (\( code, message ) -> ApiError code message metadata.statusCode)
                            |> Result.withDefault (ApiError "request_failed" (networkMessage metadata.statusCode) metadata.statusCode)
                        )

                _ ->
                    Err (ApiError "request_failed" (networkMessage 0) 0)


errorBodyDecoder : Decoder ( String, String )
errorBodyDecoder =
    Decode.map2 Tuple.pair
        (Decode.field "error" Decode.string)
        (Decode.field "message" Decode.string)


networkMessage : Int -> String
networkMessage status =
    -- The dev proxy and nginx answer 502/504 when the API is down.
    if status == 0 || status == 502 || status == 504 then
        "The banking API is unavailable. Make sure the Scalatra server is running."

    else
        "The banking request could not be completed."



-- DECODERS


overviewDecoder : Decoder AccountOverview
overviewDecoder =
    Decode.map4 AccountOverview
        (Decode.field "accountId" Decode.string)
        (Decode.field "currency" Decode.string)
        (Decode.field "balance" Decode.float)
        (Decode.oneOf [ Decode.field "activities" (Decode.list activityDecoder), Decode.succeed [] ])


activityDecoder : Decoder AccountActivity
activityDecoder =
    Decode.map8 AccountActivity
        (Decode.field "transactionId" Decode.string)
        (Decode.field "operation" Decode.string)
        (Decode.field "effect" Decode.string
            |> Decode.map
                (\effect ->
                    if effect == "increase" then
                        Increase

                    else
                        Decrease
                )
        )
        (Decode.field "amount" Decode.float)
        (Decode.field "balanceAfter" Decode.float)
        (Decode.field "occurredAt" Decode.string)
        (Decode.field "status" Decode.string)
        (Decode.maybe (Decode.field "counterpartyAccountId" Decode.string))


balanceChangeDecoder : Decoder BalanceChange
balanceChangeDecoder =
    Decode.map2 BalanceChange
        (Decode.field "accountId" Decode.string)
        (Decode.field "balance" Decode.float)


billPaymentDecoder : Decoder BillPaymentProcess
billPaymentDecoder =
    Decode.map8 BillPaymentProcess
        (Decode.field "paymentId" Decode.string)
        (Decode.field "billerCode" Decode.string)
        (Decode.field "amount" Decode.float)
        (Decode.field "status" Decode.string |> Decode.andThen paymentStatusDecoder)
        (Decode.maybe (Decode.field "resultingBalance" Decode.float))
        (Decode.maybe (Decode.field "billerReceiptCode" Decode.string))
        (Decode.maybe (Decode.field "paidAt" Decode.string))
        (Decode.maybe (Decode.at [ "failure", "message" ] Decode.string))


paymentStatusDecoder : String -> Decoder PaymentStatus
paymentStatusDecoder status =
    case status of
        "awaiting_account_charge" ->
            Decode.succeed AwaitingAccountCharge

        "settling" ->
            Decode.succeed Settling

        "reversing" ->
            Decode.succeed Reversing

        "completed" ->
            Decode.succeed Completed

        "failed" ->
            Decode.succeed Failed

        "manual_review" ->
            Decode.succeed ManualReview

        other ->
            Decode.fail ("Unknown payment status: " ++ other)



-- LABELS


isProcessing : PaymentStatus -> Bool
isProcessing status =
    status == AwaitingAccountCharge || status == Settling || status == Reversing


paymentStatusLabel : PaymentStatus -> String
paymentStatusLabel status =
    case status of
        AwaitingAccountCharge ->
            "Reserving funds"

        Settling ->
            "Contacting biller"

        Reversing ->
            "Restoring balance"

        Completed ->
            "Complete"

        Failed ->
            "Failed"

        ManualReview ->
            "Manual review"


operationLabel : String -> String
operationLabel operation =
    case operation of
        "deposit" ->
            "Deposit"

        "withdrawal" ->
            "Withdrawal"

        "transfer_out" ->
            "Transfer sent"

        "transfer_in" ->
            "Transfer received"

        "bill_payment" ->
            "Bill payment"

        "bill_payment_reversal" ->
            "Bill payment reversal"

        "adjustment" ->
            "Balance adjustment"

        other ->
            other
