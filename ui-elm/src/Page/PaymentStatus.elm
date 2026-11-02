module Page.PaymentStatus exposing (Model, Msg, init, update, view)

import Api exposing (ApiError, BillPaymentProcess, PaymentStatus(..))
import Format
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick)
import Process
import Route
import Task
import Time
import View exposing (ariaLabel, ariaLive, role)


type alias Model =
    { accountId : String
    , paymentId : String
    , payment : Maybe BillPaymentProcess
    , error : Maybe ApiError
    }


type Msg
    = Poll
    | RetryClicked
    | GotPayment (Result ApiError BillPaymentProcess)


pollIntervalMs : Float
pollIntervalMs =
    750


init : String -> String -> ( Model, Cmd Msg )
init accountId paymentId =
    let
        model =
            { accountId = accountId, paymentId = paymentId, payment = Nothing, error = Nothing }
    in
    ( model, fetch model )


fetch : Model -> Cmd Msg
fetch model =
    Api.getBillPayment model.accountId model.paymentId GotPayment


{-| Main drops a pending Poll when the user has navigated away, which ends the loop. -}
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        Poll ->
            ( model, fetch model )

        RetryClicked ->
            ( { model | error = Nothing }, fetch model )

        GotPayment (Ok payment) ->
            ( { model | payment = Just payment }
            , if Api.isProcessing payment.status then
                Process.sleep pollIntervalMs |> Task.perform (\_ -> Poll)

              else
                Cmd.none
            )

        GotPayment (Err error) ->
            ( { model | error = Just error }, Cmd.none )


view : Time.Zone -> Model -> Html Msg
view zone model =
    section [ class "form-page wide-form" ]
        [ a [ class "back-link", Route.href (Route.Dashboard model.accountId) ] [ text "← Account overview" ]
        , div [ class "flow-progress", ariaLabel "Bill payment progress" ]
            [ span [ class "active" ] [ b [] [ text "✓" ], text " Bill details" ]
            , i [] []
            , span [ class "active" ] [ b [] [ text "✓" ], text " Review" ]
            , i [] []
            , span [ class "active" ] [ b [] [ text "3" ], text " Receipt" ]
            ]
        , div [ class "card receipt-card", ariaLive "polite" ] (viewPayment zone model)
        ]


viewPayment : Time.Zone -> Model -> List (Html Msg)
viewPayment zone model =
    case ( model.error, model.payment ) of
        ( Just error, _ ) ->
            [ div [ class "notice notice-error", role "alert" ]
                [ div [] [ strong [] [ text "Unable to check this payment." ], span [] [ text error.message ] ]
                , button [ class "button button-quiet", type_ "button", onClick RetryClicked ] [ text "Try again" ]
                ]
            ]

        ( Nothing, Nothing ) ->
            [ div [ class "processing-state" ]
                [ span [ class "spinner large" ] []
                , h1 [] [ text "Finding your payment…" ]
                ]
            ]

        ( Nothing, Just payment ) ->
            if Api.isProcessing payment.status then
                [ div [ class "processing-state" ]
                    [ span [ class "processing-orbit" ] [ i [] [] ]
                    , p [ class "eyebrow" ] [ text (Api.paymentStatusLabel payment.status) ]
                    , h1 [] [ text "Your payment is in motion" ]
                    , p [] [ text "Keep this page open. We'll update it as soon as the account and biller finish processing." ]
                    , small [] [ text ("Payment " ++ String.toUpper (String.left 8 payment.paymentId)) ]
                    ]
                ]

            else if payment.status == Completed then
                [ div [ class "receipt-heading success" ]
                    [ span [ class "success-mark" ] [ text "✓" ]
                    , p [ class "eyebrow" ] [ text "Payment complete" ]
                    , h1 [] [ text (Format.thb payment.amount) ]
                    , p [] [ text "Paid to ", strong [] [ text payment.billerCode ] ]
                    ]
                , dl [ class "receipt-details" ]
                    [ detail "Receipt" [] (Maybe.withDefault "" payment.billerReceiptCode)
                    , detail "Paid at" [] (payment.paidAt |> Maybe.map (Format.dateTime zone) |> Maybe.withDefault "")
                    , detail "Remaining balance" [] (payment.resultingBalance |> Maybe.map Format.thb |> Maybe.withDefault "")
                    , detail "Payment ID" [ class "mono" ] payment.paymentId
                    ]
                , returnLink model
                ]

            else
                let
                    manualReview =
                        payment.status == ManualReview
                in
                [ div [ class "receipt-heading failed" ]
                    [ span [ class "failure-mark" ] [ text "!" ]
                    , p [ class "eyebrow" ]
                        [ text
                            (if manualReview then
                                "Manual review"

                             else
                                "Payment not completed"
                            )
                        ]
                    , h1 []
                        [ text
                            (if manualReview then
                                "We’re checking this payment"

                             else
                                "Your money is safe"
                            )
                        ]
                    , p [] [ text (Maybe.withDefault "The payment could not be completed." payment.failureMessage) ]
                    ]
                , case payment.resultingBalance of
                    Just balance ->
                        p [ class "balance-note" ] [ text "Available balance: ", strong [] [ text (Format.thb balance) ] ]

                    Nothing ->
                        text ""
                , returnLink model
                ]


detail : String -> List (Attribute msg) -> String -> Html msg
detail term attributes description =
    div [] [ dt [] [ text term ], dd attributes [ text description ] ]


returnLink : Model -> Html msg
returnLink model =
    a [ class "button button-primary button-block", Route.href (Route.Dashboard model.accountId) ]
        [ text "Return to account" ]
