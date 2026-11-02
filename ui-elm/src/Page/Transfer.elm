module Page.Transfer exposing (Model, Msg, init, update, view)

import Api exposing (ApiError, TransferResult)
import Format
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onBlur, onInput, onSubmit)
import Route
import View exposing (ariaInvalid, ariaLive)


type alias Model =
    { accountId : String
    , destinationAccountId : String
    , destinationTouched : Bool
    , amount : String
    , amountTouched : Bool
    , submitting : Bool
    , error : Maybe ApiError
    , result : Maybe TransferResult
    }


type Msg
    = DestinationChanged String
    | DestinationTouched
    | AmountChanged String
    | AmountTouched
    | Submitted
    | Transferred (Result ApiError TransferResult)


init : String -> Model
init accountId =
    { accountId = accountId
    , destinationAccountId = ""
    , destinationTouched = False
    , amount = ""
    , amountTouched = False
    , submitting = False
    , error = Nothing
    , result = Nothing
    }


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        DestinationChanged destinationAccountId ->
            ( { model | destinationAccountId = destinationAccountId }, Cmd.none )

        DestinationTouched ->
            ( { model | destinationTouched = True }, Cmd.none )

        AmountChanged amount ->
            ( { model | amount = amount }, Cmd.none )

        AmountTouched ->
            ( { model | amountTouched = True }, Cmd.none )

        Submitted ->
            let
                touchedModel =
                    { model | destinationTouched = True, amountTouched = True }
            in
            case ( destinationValid model, Format.isValidAmount model.amount, String.toFloat model.amount ) of
                ( True, True, Just amount ) ->
                    if model.submitting then
                        ( touchedModel, Cmd.none )

                    else
                        ( { touchedModel | submitting = True, error = Nothing }
                        , Api.transfer model.accountId model.destinationAccountId amount Transferred
                        )

                _ ->
                    ( touchedModel, Cmd.none )

        Transferred (Ok result) ->
            ( { model | submitting = False, result = Just result }, Cmd.none )

        Transferred (Err error) ->
            ( { model | submitting = False, error = Just error }, Cmd.none )


sameAccount : Model -> Bool
sameAccount model =
    model.destinationAccountId == model.accountId


destinationValid : Model -> Bool
destinationValid model =
    Format.isValidAccountId model.destinationAccountId && not (sameAccount model)


view : Model -> Html Msg
view model =
    let
        destinationError =
            not (destinationValid model) && model.destinationTouched

        amountInvalid =
            not (Format.isValidAmount model.amount)
    in
    section [ class "form-page" ]
        [ a [ class "back-link", Route.href (Route.Dashboard model.accountId) ] [ text "← Account overview" ]
        , div [ class "card form-card" ]
            [ p [ class "eyebrow" ] [ text "Account transfer" ]
            , h1 [] [ text "Send money instantly" ]
            , p [] [ text "The debit and credit are recorded together as one atomic ledger transaction." ]
            , case model.result of
                Nothing ->
                    Html.form [ onSubmit Submitted, novalidate True ]
                        ([ label [ for "destinationAccountId" ] [ text "Destination account" ]
                         , input
                            [ id "destinationAccountId"
                            , type_ "text"
                            , autocomplete False
                            , placeholder "recipient-account"
                            , value model.destinationAccountId
                            , ariaInvalid destinationError
                            , onInput DestinationChanged
                            , onBlur DestinationTouched
                            ]
                            []
                         , if destinationError then
                            p [ class "field-error" ]
                                [ text
                                    (if sameAccount model then
                                        "Choose an account other than the source account."

                                     else
                                        "Enter a valid account ID."
                                    )
                                ]

                           else
                            text ""
                         ]
                            ++ View.amountInput
                                { value = model.amount
                                , showError = amountInvalid && model.amountTouched
                                , onChange = AmountChanged
                                , onTouch = AmountTouched
                                }
                            ++ [ View.errorNotice model.error
                               , button
                                    [ class "button button-primary button-block"
                                    , type_ "submit"

                                    -- Like the Angular form, the same-account check runs on submit.
                                    , disabled
                                        (not (Format.isValidAccountId model.destinationAccountId)
                                            || amountInvalid
                                            || model.submitting
                                        )
                                    ]
                                    [ text
                                        (if model.submitting then
                                            "Transferring…"

                                         else
                                            "Confirm transfer"
                                        )
                                    ]
                               ]
                        )

                Just result ->
                    div [ class "success-state", ariaLive "polite" ]
                        [ span [ class "success-mark" ] [ text "✓" ]
                        , h2 [] [ text "Transfer complete" ]
                        , p []
                            [ text (Format.thb result.amount ++ " sent to ")
                            , strong [] [ text result.destinationAccountId ]
                            ]
                        , p [] [ text "Your available balance is now" ]
                        , strong [] [ text (Format.thb result.sourceBalance) ]
                        , small [ class "mono" ] [ text ("Transfer " ++ result.transferId) ]
                        , a [ class "button button-primary button-block", Route.href (Route.Dashboard model.accountId) ]
                            [ text "Return to account" ]
                        ]
            ]
        ]
