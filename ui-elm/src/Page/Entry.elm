module Page.Entry exposing (Model, Msg, init, update, view)

import Api exposing (ApiError)
import Browser.Navigation as Nav
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick)
import Route
import View exposing (ariaHidden, ariaLabel)


type alias Model =
    { accountId : String
    , touched : Bool
    , submitting : Bool
    , error : Maybe ApiError
    }


type Msg
    = DemoAccountClicked String


init : Model
init =
    { accountId = "", touched = False, submitting = False, error = Nothing }


update : Nav.Key -> Msg -> Model -> ( Model, Cmd Msg )
update key msg model =
    case msg of
        DemoAccountClicked accountId ->
            ( model, Route.push key (Route.Dashboard accountId) )


view : Model -> Html Msg
view model =
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
        , section [ class "card account-card", attribute "aria-labelledby" "account-selection-title" ]
            [ p [ class "step-label" ] [ text "Demo accounts" ]
            , h2 [ id "account-selection-title" ] [ text "Choose an account" ]
            , p [] [ text "Each demo account starts with THB 100.00." ]
            , Html.nav [ class "demo-accounts", ariaLabel "Select a demo account" ]
                [ button
                    [ class "button button-primary button-block"
                    , onClick (DemoAccountClicked "acc123")
                    ]
                    [ text "acc123 ", span [ ariaHidden ] [ text "→" ] ]
                , button
                    [ class "button button-primary button-block"
                    , onClick (DemoAccountClicked "acc456")
                    ]
                    [ text "acc456 ", span [ ariaHidden ] [ text "→" ] ]
                ]
            ]
        ]
