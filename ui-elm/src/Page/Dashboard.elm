module Page.Dashboard exposing (Model, Msg, init, update, view)

import Api exposing (AccountActivity, AccountOverview, ApiError, Effect(..))
import Format
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick)
import Route exposing (MoneyOperation(..), Route)
import Time
import View exposing (ariaHidden, ariaLabel, role)


type alias Model =
    { accountId : String
    , account : Account
    , refreshing : Bool
    , refreshError : Maybe ApiError
    }


type Account
    = Loading
    | Missing
    | LoadFailed ApiError
    | Loaded AccountOverview


type Msg
    = Reload
    | GotOverview (Result ApiError AccountOverview)


init : String -> ( Model, Cmd Msg )
init accountId =
    ( { accountId = accountId, account = Loading, refreshing = False, refreshError = Nothing }
    , Api.getAccountOverview accountId GotOverview
    )


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        Reload ->
            case model.account of
                Loaded _ ->
                    ( { model | refreshing = True, refreshError = Nothing }
                    , Api.getAccountOverview model.accountId GotOverview
                    )

                _ ->
                    init model.accountId

        GotOverview (Ok overview) ->
            ( { model
                | account = Loaded { overview | activities = List.reverse overview.activities }
                , refreshing = False
              }
            , Cmd.none
            )

        GotOverview (Err error) ->
            case model.account of
                Loaded _ ->
                    ( { model | refreshing = False, refreshError = Just error }, Cmd.none )

                _ ->
                    if error.code == "account_not_found" then
                        ( { model | account = Missing }, Cmd.none )

                    else
                        ( { model | account = LoadFailed error }, Cmd.none )


view : Time.Zone -> Model -> Html Msg
view zone model =
    div []
        [ section [ class "dashboard-heading" ]
            [ div []
                [ a [ class "back-link", Route.href Route.Entry ] [ text "← Switch account" ]
                , p [ class "eyebrow" ] [ text "Account overview" ]
                , h1 [] [ text model.accountId ]
                ]
            , button
                [ class "button button-quiet"
                , type_ "button"
                , onClick Reload
                , disabled (model.account == Loading || model.refreshing)
                ]
                [ text
                    (if model.refreshing then
                        "Refreshing…"

                     else
                        "Refresh"
                    )
                ]
            ]
        , case model.refreshError of
            Just error ->
                div [ class "notice notice-error refresh-notice", role "alert" ]
                    [ div [] [ strong [] [ text "We couldn't refresh this account." ], span [] [ text error.message ] ]
                    , button [ class "button button-quiet", type_ "button", onClick Reload ] [ text "Try again" ]
                    ]

            Nothing ->
                text ""
        , case model.account of
            Loading ->
                skeleton

            LoadFailed error ->
                div [ class "notice notice-error", role "alert" ]
                    [ div [] [ strong [] [ text "We couldn't load this account." ], span [] [ text error.message ] ]
                    , button [ class "button button-quiet", type_ "button", onClick Reload ] [ text "Try again" ]
                    ]

            Missing ->
                section [ class "empty-account card" ]
                    [ span [ class "empty-icon", ariaHidden ] [ text "＋" ]
                    , p [ class "eyebrow" ] [ text "New account" ]
                    , h2 [] [ text "Start with your first deposit" ]
                    , p [] [ text "No ledger entries exist for ", strong [] [ text model.accountId ], text " yet." ]
                    , a [ class "button button-primary", Route.href (Route.Money Deposit model.accountId) ] [ text "Make a deposit" ]
                    ]

            Loaded overview ->
                viewAccount zone model overview
        ]


viewAccount : Time.Zone -> Model -> AccountOverview -> Html Msg
viewAccount zone model overview =
    div
        [ class "account-content"
        , attribute "aria-busy"
            (if model.refreshing then
                "true"

             else
                "false"
            )
        ]
        [ section [ class "balance-layout" ]
            [ article [ class "balance-card" ]
                [ p [] [ text "Available balance" ]
                , strong [] [ text (Format.thb overview.balance) ]
                , span [] [ text (overview.currency ++ " · Ledger derived") ]
                , div [ class "balance-decoration", ariaHidden ] []
                ]
            , nav [ class "quick-actions", ariaLabel "Account actions" ]
                [ actionCard (Route.Money Deposit model.accountId) "increase" "＋" "Deposit" "Add funds"
                , actionCard (Route.Money Withdraw model.accountId) "decrease" "−" "Withdraw" "Take out funds"
                , actionCard (Route.Transfer model.accountId) "transfer" "→" "Transfer" "Send to an account"
                , actionCard (Route.BillPayment model.accountId) "bill" "↗" "Pay a bill" "Settle a biller"
                ]
            ]
        , section [ class "activity-section" ]
            [ div [ class "section-heading" ]
                [ div [] [ p [ class "eyebrow" ] [ text "Immutable record" ], h2 [] [ text "Recent activity" ] ]
                , span [] [ text (String.fromInt (List.length overview.activities) ++ " entries") ]
                ]
            , if List.isEmpty overview.activities then
                div [ class "card empty-list" ] [ text "No activity has been recorded yet." ]

              else
                div [ class "activity-list" ] (List.map (activityRow zone) overview.activities)
            ]
        ]


actionCard : Route -> String -> String -> String -> String -> Html msg
actionCard route iconClass icon title subtitle =
    a [ class "action-card", Route.href route ]
        [ span [ class ("action-icon " ++ iconClass) ] [ text icon ]
        , strong [] [ text title ]
        , small [] [ text subtitle ]
        ]


activityRow : Time.Zone -> AccountActivity -> Html msg
activityRow zone activity =
    let
        increase =
            activity.effect == Increase

        reversed =
            activity.status == "reversed"
    in
    article [ class "activity-row", classList [ ( "reversed", reversed ) ] ]
        [ span [ class "activity-icon", classList [ ( "increase", increase ) ] ]
            [ text
                (if increase then
                    "↓"

                 else
                    "↑"
                )
            ]
        , div [ class "activity-name" ]
            (strong [] [ text (Api.operationLabel activity.operation) ]
                :: span [] [ text (Format.dateTime zone activity.occurredAt) ]
                :: (case activity.counterpartyAccountId of
                        Just counterparty ->
                            [ span [] [ text ("Account " ++ counterparty) ] ]

                        Nothing ->
                            []
                   )
            )
        , div [ class "activity-status" ]
            [ span [ class "status-chip", classList [ ( "status-reversed", reversed ) ] ] [ text activity.status ] ]
        , div [ class "activity-amount", classList [ ( "positive", increase ) ] ]
            [ strong []
                [ text
                    ((if increase then
                        "+"

                      else
                        "−"
                     )
                        ++ Format.thb activity.amount
                    )
                ]
            , span [] [ text ("Balance " ++ Format.thb activity.balanceAfter) ]
            ]
        ]


skeleton : Html msg
skeleton =
    let
        line extra =
            span [ class ("skeleton-line" ++ extra) ] []

        circle =
            span [ class "skeleton-circle" ] []

        actionCardSkeleton =
            div [ class "action-card skeleton-surface" ] [ circle, line "", line " skeleton-short" ]

        activityRowSkeleton =
            div [ class "activity-row" ] [ circle, line "", line "", line "" ]
    in
    div []
        [ span [ class "visually-hidden", role "status" ] [ text "Loading account details…" ]
        , div [ class "account-skeleton", ariaHidden ]
            [ section [ class "balance-layout" ]
                [ article [ class "balance-card skeleton-surface" ]
                    [ line " skeleton-label", line " skeleton-balance", line " skeleton-meta" ]
                , div [ class "quick-actions" ] (List.repeat 4 actionCardSkeleton)
                ]
            , section [ class "activity-section" ]
                [ div [ class "section-heading" ]
                    [ div [] [ line " skeleton-label", line " skeleton-heading" ] ]
                , div [ class "activity-list skeleton-activities" ] (List.repeat 3 activityRowSkeleton)
                ]
            ]
        ]
