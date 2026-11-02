module Page.BillPayment exposing (Model, Msg, init, subscriptions, update, view)

import Api exposing (ApiError, BillPaymentAccepted, BillPaymentInquiry, BillRequest)
import Browser.Navigation as Nav
import Format
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onBlur, onClick, onInput, onSubmit)
import Iso8601
import Route
import Task
import Time
import View exposing (ariaInvalid, ariaLabel)


type alias Model =
    { accountId : String
    , bill : BillRequest
    , touched : Bool
    , inquiry : Maybe BillPaymentInquiry
    , submitting : Bool
    , error : Maybe ApiError
    , now : Maybe Time.Posix
    }


type Field
    = BillerCode
    | ReferenceCode1
    | ReferenceCode2


type Msg
    = FieldChanged Field String
    | Touched
    | FindBillSubmitted
    | GotInquiry (Result ApiError BillPaymentInquiry)
    | ConfirmClicked
    | Confirmed (Result ApiError BillPaymentAccepted)
    | ChangeDetailsClicked
    | Tick Time.Posix


init : String -> ( Model, Cmd Msg )
init accountId =
    ( { accountId = accountId
      , bill = { billerCode = "demo-biller", referenceCode1 = "customer-001", referenceCode2 = "invoice-001" }
      , touched = False
      , inquiry = Nothing
      , submitting = False
      , error = Nothing
      , now = Nothing
      }
    , Task.perform Tick Time.now
    )


{-| Re-check the quote's expiry while it is on screen. -}
subscriptions : Model -> Sub Msg
subscriptions model =
    case model.inquiry of
        Just _ ->
            Time.every 1000 Tick

        Nothing ->
            Sub.none


update : Nav.Key -> Msg -> Model -> ( Model, Cmd Msg )
update key msg model =
    case msg of
        FieldChanged changedField value ->
            let
                bill =
                    model.bill
            in
            ( { model
                | bill =
                    case changedField of
                        BillerCode ->
                            { bill | billerCode = value }

                        ReferenceCode1 ->
                            { bill | referenceCode1 = value }

                        ReferenceCode2 ->
                            { bill | referenceCode2 = value }
              }
            , Cmd.none
            )

        Touched ->
            ( { model | touched = True }, Cmd.none )

        FindBillSubmitted ->
            if formInvalid model || model.submitting then
                ( { model | touched = True }, Cmd.none )

            else
                ( { model | touched = True, submitting = True, error = Nothing }
                , Api.inquireBill model.accountId model.bill GotInquiry
                )

        GotInquiry (Ok inquiry) ->
            ( { model | submitting = False, inquiry = Just inquiry }, Cmd.none )

        GotInquiry (Err error) ->
            ( { model | submitting = False, error = Just error }, Cmd.none )

        ConfirmClicked ->
            case model.inquiry of
                Just inquiry ->
                    if model.submitting || quoteExpired model then
                        ( model, Cmd.none )

                    else
                        ( { model | submitting = True, error = Nothing }
                        , Api.confirmBillPayment model.accountId inquiry.inquiryId Confirmed
                        )

                Nothing ->
                    ( model, Cmd.none )

        Confirmed (Ok accepted) ->
            ( model, Route.push key (Route.PaymentStatus model.accountId accepted.paymentId) )

        Confirmed (Err error) ->
            ( { model | submitting = False, error = Just error }, Cmd.none )

        ChangeDetailsClicked ->
            ( { model | inquiry = Nothing, error = Nothing }, Cmd.none )

        Tick now ->
            ( { model | now = Just now }, Cmd.none )


formInvalid : Model -> Bool
formInvalid model =
    List.any (String.trim >> String.isEmpty)
        [ model.bill.billerCode, model.bill.referenceCode1, model.bill.referenceCode2 ]


quoteExpired : Model -> Bool
quoteExpired model =
    case ( model.inquiry, model.now ) of
        ( Just inquiry, Just now ) ->
            case Iso8601.toTime inquiry.expiresAt of
                Ok expiresAt ->
                    Time.posixToMillis expiresAt <= Time.posixToMillis now

                Err _ ->
                    False

        _ ->
            False


view : Time.Zone -> Model -> Html Msg
view zone model =
    section [ class "form-page wide-form" ]
        [ a [ class "back-link", Route.href (Route.Dashboard model.accountId) ] [ text "← Account overview" ]
        , div [ class "flow-progress", ariaLabel "Bill payment progress" ]
            [ span [ class "active" ] [ b [] [ text "1" ], text " Bill details" ]
            , i [] []
            , span [ classList [ ( "active", model.inquiry /= Nothing ) ] ] [ b [] [ text "2" ], text " Review" ]
            , i [] []
            , span [] [ b [] [ text "3" ], text " Receipt" ]
            ]
        , div [ class "card form-card" ]
            (case model.inquiry of
                Nothing ->
                    viewFindBill model

                Just inquiry ->
                    viewReview zone model inquiry
            )
        ]


viewFindBill : Model -> List (Html Msg)
viewFindBill model =
    [ p [ class "eyebrow" ] [ text "Find your bill" ]
    , h1 [] [ text "Who are you paying?" ]
    , p [] [ text "We'll ask the biller for the current amount before anything leaves your account." ]
    , Html.form [ onSubmit FindBillSubmitted, novalidate True ]
        (field model BillerCode "biller-code" "Biller code" model.bill.billerCode
            ++ [ div [ class "field-grid" ]
                    [ div [] (field model ReferenceCode1 "reference-1" "Reference code 1" model.bill.referenceCode1)
                    , div [] (field model ReferenceCode2 "reference-2" "Reference code 2" model.bill.referenceCode2)
                    ]
               , if formInvalid model && model.touched then
                    p [ class "field-error" ] [ text "All biller and reference fields are required." ]

                 else
                    text ""
               , View.errorNotice model.error
               , button
                    [ class "button button-primary button-block"
                    , type_ "submit"
                    , disabled (formInvalid model || model.submitting)
                    ]
                    [ text
                        (if model.submitting then
                            "Looking up bill…"

                         else
                            "Check current amount"
                        )
                    ]
               ]
        )
    , p [ class "demo-hint" ] [ text "Demo bill details are filled in for you." ]
    ]


field : Model -> Field -> String -> String -> String -> List (Html Msg)
field model fieldName fieldId fieldLabel fieldValue =
    [ label [ for fieldId ] [ text fieldLabel ]
    , input
        [ id fieldId
        , type_ "text"
        , autocomplete False
        , value fieldValue
        , ariaInvalid (model.touched && String.isEmpty (String.trim fieldValue))
        , onInput (FieldChanged fieldName)
        , onBlur Touched
        ]
        []
    ]


viewReview : Time.Zone -> Model -> BillPaymentInquiry -> List (Html Msg)
viewReview zone model inquiry =
    let
        expired =
            quoteExpired model
    in
    [ p [ class "eyebrow" ] [ text "Review payment" ]
    , h1 [] [ text "Confirm the details" ]
    , p [] [ text "No payment is made until you confirm this current bill amount." ]
    , div [ class "bill-summary" ]
        [ div [ class "bill-amount" ]
            [ span [] [ text "Amount due" ]
            , strong [] [ text (Format.thb inquiry.currentDebt) ]
            , small [] [ text "THB" ]
            ]
        , dl []
            [ detail "Biller" inquiry.billerCode
            , detail "Reference 1" inquiry.referenceCode1
            , detail "Reference 2" inquiry.referenceCode2
            , detail "Quote expires" (Format.time zone inquiry.expiresAt)
            ]
        ]
    , View.errorNotice model.error
    , div [ class "button-row" ]
        [ button
            [ class "button button-quiet", type_ "button", onClick ChangeDetailsClicked, disabled model.submitting ]
            [ text "Change details" ]
        , button
            [ class "button button-primary", type_ "button", onClick ConfirmClicked, disabled (model.submitting || expired) ]
            [ text
                (if model.submitting then
                    "Submitting…"

                 else
                    "Confirm payment"
                )
            ]
        ]
    , if expired then
        p [ class "field-error centered" ] [ text "This quote has expired. Please look up the bill again." ]

      else
        text ""
    ]


detail : String -> String -> Html msg
detail term description =
    div [] [ dt [] [ text term ], dd [] [ text description ] ]
