module Main exposing (main)

import Browser
import Browser.Navigation as Nav
import Html exposing (..)
import Html.Attributes exposing (..)
import Page.BillPayment as BillPayment
import Page.Dashboard as Dashboard
import Page.Entry as Entry
import Page.MoneyOperation as MoneyOperation
import Page.PaymentStatus as PaymentStatus
import Page.Transfer as Transfer
import Route exposing (Route)
import Task
import Time
import Url exposing (Url)
import View exposing (ariaHidden, ariaLabel)


main : Program () Model Msg
main =
    Browser.application
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        , onUrlRequest = LinkClicked
        , onUrlChange = UrlChanged
        }


type alias Model =
    { key : Nav.Key
    , zone : Time.Zone
    , page : Page
    }


type Page
    = EntryPage Entry.Model
    | DashboardPage Dashboard.Model
    | MoneyOperationPage MoneyOperation.Model
    | TransferPage Transfer.Model
    | BillPaymentPage BillPayment.Model
    | PaymentStatusPage PaymentStatus.Model


type Msg
    = LinkClicked Browser.UrlRequest
    | UrlChanged Url
    | GotZone Time.Zone
    | EntryMsg Entry.Msg
    | DashboardMsg Dashboard.Msg
    | MoneyOperationMsg MoneyOperation.Msg
    | TransferMsg Transfer.Msg
    | BillPaymentMsg BillPayment.Msg
    | PaymentStatusMsg PaymentStatus.Msg


init : () -> Url -> Nav.Key -> ( Model, Cmd Msg )
init _ url key =
    let
        ( model, pageCmd ) =
            changeRouteTo url { key = key, zone = Time.utc, page = EntryPage Entry.init }
    in
    ( model, Cmd.batch [ pageCmd, Task.perform GotZone Time.here ] )


{-| Unknown URLs redirect to the entry page, like the Angular `**` route. -}
changeRouteTo : Url -> Model -> ( Model, Cmd Msg )
changeRouteTo url model =
    case Route.fromUrl url of
        Nothing ->
            ( { model | page = EntryPage Entry.init }, Nav.replaceUrl model.key "/" )

        Just Route.Entry ->
            ( { model | page = EntryPage Entry.init }, Cmd.none )

        Just (Route.Dashboard accountId) ->
            Dashboard.init accountId |> updateWith DashboardPage DashboardMsg model

        Just (Route.Money operation accountId) ->
            ( { model | page = MoneyOperationPage (MoneyOperation.init operation accountId) }, Cmd.none )

        Just (Route.Transfer accountId) ->
            ( { model | page = TransferPage (Transfer.init accountId) }, Cmd.none )

        Just (Route.BillPayment accountId) ->
            BillPayment.init accountId |> updateWith BillPaymentPage BillPaymentMsg model

        Just (Route.PaymentStatus accountId paymentId) ->
            PaymentStatus.init accountId paymentId |> updateWith PaymentStatusPage PaymentStatusMsg model


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case ( msg, model.page ) of
        ( LinkClicked (Browser.Internal url), _ ) ->
            ( model, Nav.pushUrl model.key (Url.toString url) )

        ( LinkClicked (Browser.External href), _ ) ->
            ( model, Nav.load href )

        ( UrlChanged url, _ ) ->
            changeRouteTo url model

        ( GotZone zone, _ ) ->
            ( { model | zone = zone }, Cmd.none )

        ( EntryMsg pageMsg, EntryPage page ) ->
            Entry.update model.key pageMsg page |> updateWith EntryPage EntryMsg model

        ( DashboardMsg pageMsg, DashboardPage page ) ->
            Dashboard.update pageMsg page |> updateWith DashboardPage DashboardMsg model

        ( MoneyOperationMsg pageMsg, MoneyOperationPage page ) ->
            MoneyOperation.update model.key pageMsg page |> updateWith MoneyOperationPage MoneyOperationMsg model

        ( TransferMsg pageMsg, TransferPage page ) ->
            Transfer.update pageMsg page |> updateWith TransferPage TransferMsg model

        ( BillPaymentMsg pageMsg, BillPaymentPage page ) ->
            BillPayment.update model.key pageMsg page |> updateWith BillPaymentPage BillPaymentMsg model

        ( PaymentStatusMsg pageMsg, PaymentStatusPage page ) ->
            PaymentStatus.update pageMsg page |> updateWith PaymentStatusPage PaymentStatusMsg model

        -- A response or timer for a page the user has already left.
        _ ->
            ( model, Cmd.none )


updateWith : (pageModel -> Page) -> (pageMsg -> Msg) -> Model -> ( pageModel, Cmd pageMsg ) -> ( Model, Cmd Msg )
updateWith toPage toMsg model ( pageModel, pageCmd ) =
    ( { model | page = toPage pageModel }, Cmd.map toMsg pageCmd )


subscriptions : Model -> Sub Msg
subscriptions model =
    case model.page of
        BillPaymentPage page ->
            Sub.map BillPaymentMsg (BillPayment.subscriptions page)

        _ ->
            Sub.none


view : Model -> Browser.Document Msg
view model =
    { title = "Ledger Bank"
    , body =
        [ header [ class "site-header" ]
            [ a [ class "brand", Route.href Route.Entry, ariaLabel "Ledger Bank home" ]
                [ span [ class "brand-mark", ariaHidden ] [ text "L" ]
                , span [] [ strong [] [ text "Ledger" ], small [] [ text "Simple banking, clearly recorded" ] ]
                ]
            , span [ class "environment-pill" ] [ text "In-memory demo · Elm" ]
            ]
        , main_ [ class "page-shell" ] [ viewPage model ]
        , footer [ class "site-footer" ]
            [ span [] [ text "Every movement is backed by a balanced ledger entry." ]
            , a [ Route.href Route.Entry ] [ text "Switch account" ]
            ]
        ]
    }


viewPage : Model -> Html Msg
viewPage model =
    case model.page of
        EntryPage page ->
            Html.map EntryMsg (Entry.view page)

        DashboardPage page ->
            Html.map DashboardMsg (Dashboard.view model.zone page)

        MoneyOperationPage page ->
            Html.map MoneyOperationMsg (MoneyOperation.view page)

        TransferPage page ->
            Html.map TransferMsg (Transfer.view page)

        BillPaymentPage page ->
            Html.map BillPaymentMsg (BillPayment.view model.zone page)

        PaymentStatusPage page ->
            Html.map PaymentStatusMsg (PaymentStatus.view model.zone page)
