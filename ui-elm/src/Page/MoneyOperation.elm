module Page.MoneyOperation exposing (Model, Msg, init, update, view)

import Api exposing (ApiError, BalanceChange)
import Browser.Navigation as Nav
import Format
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onSubmit)
import Route exposing (MoneyOperation(..))
import View exposing (ariaLive)


type alias Model =
    { operation : MoneyOperation
    , accountId : String
    , amount : String
    , touched : Bool
    , submitting : Bool
    , error : Maybe ApiError
    , result : Maybe BalanceChange
    }


type Msg
    = AmountChanged String
    | Touched
    | Submitted
    | Recorded (Result ApiError BalanceChange)


init : MoneyOperation -> String -> Model
init operation accountId =
    { operation = operation
    , accountId = accountId
    , amount = ""
    , touched = False
    , submitting = False
    , error = Nothing
    , result = Nothing
    }


update : Nav.Key -> Msg -> Model -> ( Model, Cmd Msg )
update key msg model =
    case msg of
        AmountChanged amount ->
            ( { model | amount = amount }, Cmd.none )

        Touched ->
            ( { model | touched = True }, Cmd.none )

        Submitted ->
            case ( Format.isValidAmount model.amount, String.toFloat model.amount ) of
                ( True, Just amount ) ->
                    if model.submitting then
                        ( model, Cmd.none )

                    else
                        ( { model | touched = True, submitting = True, error = Nothing }
                        , case model.operation of
                            Deposit ->
                                Api.deposit model.accountId amount Recorded

                            Withdraw ->
                                Api.withdraw model.accountId amount Recorded
                        )

                _ ->
                    ( { model | touched = True }, Cmd.none )

        Recorded (Ok result) ->
            case model.operation of
                Deposit ->
                    ( model, Route.push key (Route.Dashboard model.accountId) )

                Withdraw ->
                    ( { model | submitting = False, result = Just result }, Cmd.none )

        Recorded (Err error) ->
            ( { model | submitting = False, error = Just error }, Cmd.none )


view : Model -> Html Msg
view model =
    let
        ( title, heading, description ) =
            case model.operation of
                Deposit ->
                    ( "Deposit"
                    , "Add money to your account"
                    , "Your deposit is recorded immediately as a balanced ledger transaction."
                    )

                Withdraw ->
                    ( "Withdraw"
                    , "Withdraw from your balance"
                    , "The withdrawal will complete only when sufficient funds are available."
                    )

        invalid =
            not (Format.isValidAmount model.amount)
    in
    section [ class "form-page" ]
        [ a [ class "back-link", Route.href (Route.Dashboard model.accountId) ] [ text "← Account overview" ]
        , div [ class "card form-card" ]
            [ p [ class "eyebrow" ] [ text (title ++ " funds") ]
            , h1 [] [ text heading ]
            , p [] [ text description ]
            , case model.result of
                Nothing ->
                    Html.form [ onSubmit Submitted, novalidate True ]
                        (View.amountInput
                            { value = model.amount
                            , showError = invalid && model.touched
                            , onChange = AmountChanged
                            , onTouch = Touched
                            }
                            ++ [ View.errorNotice model.error
                               , button
                                    [ class "button button-primary button-block"
                                    , type_ "submit"
                                    , disabled (invalid || model.submitting)
                                    ]
                                    [ text
                                        (if model.submitting then
                                            "Recording…"

                                         else if model.operation == Deposit then
                                            "Confirm deposit"

                                         else
                                            "Confirm withdrawal"
                                        )
                                    ]
                               ]
                        )

                Just result ->
                    div [ class "success-state", ariaLive "polite" ]
                        [ span [ class "success-mark" ] [ text "✓" ]
                        , h2 [] [ text (title ++ " complete") ]
                        , p [] [ text "Your available balance is now" ]
                        , strong [] [ text (Format.thb result.balance) ]
                        , a [ class "button button-primary button-block", Route.href (Route.Dashboard model.accountId) ]
                            [ text "Return to account" ]
                        ]
            ]
        ]
