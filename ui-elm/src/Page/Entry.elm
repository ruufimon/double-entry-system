module Page.Entry exposing (Model, Msg, init, update, view)

import Api exposing (ApiError)
import Browser.Navigation as Nav
import Format
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onBlur, onClick, onInput, onSubmit)
import Route
import View exposing (ariaHidden, ariaInvalid, ariaLabel)


type alias Model =
    { accountId : String
    , touched : Bool
    , submitting : Bool
    , error : Maybe ApiError
    }


type Msg
    = AccountIdChanged String
    | Touched
    | Submitted
    | DemoAccountClicked
    | Opened String (Result ApiError ())


init : Model
init =
    { accountId = "", touched = False, submitting = False, error = Nothing }


update : Nav.Key -> Msg -> Model -> ( Model, Cmd Msg )
update key msg model =
    case msg of
        AccountIdChanged accountId ->
            ( { model | accountId = accountId }, Cmd.none )

        Touched ->
            ( { model | touched = True }, Cmd.none )

        Submitted ->
            openAccount { model | touched = True }

        DemoAccountClicked ->
            openAccount { model | accountId = "account-123", touched = True }

        Opened accountId (Ok ()) ->
            ( model, Route.push key (Route.Dashboard accountId) )

        Opened _ (Err error) ->
            ( { model | submitting = False, error = Just error }, Cmd.none )


openAccount : Model -> ( Model, Cmd Msg )
openAccount model =
    if Format.isValidAccountId model.accountId then
        let
            accountId =
                String.trim model.accountId
        in
        ( { model | submitting = True, error = Nothing }
        , Api.openAccount accountId (Opened accountId)
        )

    else
        ( model, Cmd.none )


view : Model -> Html Msg
view model =
    let
        invalid =
            not (Format.isValidAccountId model.accountId)
    in
    section [ class "hero-grid" ]
        [ div [ class "hero-copy" ]
            [ p [ class "eyebrow" ] [ text "Every baht, accounted for" ]
            , h1 [] [ text "Your balance.", br [] [], em [] [ text "Nothing hidden." ] ]
            , p [ class "hero-description" ]
                [ text "Deposit, withdraw, pay bills, and follow every movement through an immutable double-entry ledger." ]
            , div [ class "trust-row", ariaLabel "Product characteristics" ]
                [ span [] [ text "Balanced entries" ]
                , span [] [ text "Clear activity" ]
                , span [] [ text "Instant feedback" ]
                ]
            ]
        , Html.form [ class "card account-card", onSubmit Submitted, novalidate True ]
            [ p [ class "step-label" ] [ text "Start here" ]
            , h2 [] [ text "Open an account" ]
            , p [] [ text "Enter an account ID. A new account is created by making its first deposit." ]
            , label [ for "account-id" ] [ text "Account ID" ]
            , input
                [ id "account-id"
                , type_ "text"
                , attribute "autocomplete" "username"
                , placeholder "e.g. account-123"
                , value model.accountId
                , ariaInvalid (invalid && model.touched)
                , onInput AccountIdChanged
                , onBlur Touched
                ]
                []
            , if invalid && model.touched then
                p [ class "field-error" ] [ text "Use 1–64 letters, numbers, underscores, or hyphens." ]

              else
                text ""
            , View.errorNotice model.error
            , button
                [ class "button button-primary button-block"
                , type_ "submit"
                , disabled (invalid || model.submitting)
                ]
                [ text
                    (if model.submitting then
                        "Opening account…"

                     else
                        "Continue to account"
                    )
                , span [ ariaHidden ] [ text "→" ]
                ]
            , p [ class "demo-hint" ]
                [ text "Try ", button [ type_ "button", onClick DemoAccountClicked ] [ text "account-123" ] ]
            ]
        ]
