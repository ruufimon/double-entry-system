module Route exposing (MoneyOperation(..), Route(..), fromUrl, href, push)

import Browser.Navigation as Nav
import Html exposing (Attribute)
import Html.Attributes
import Url exposing (Url)
import Url.Builder
import Url.Parser as Parser exposing ((</>), Parser, s, string)


type MoneyOperation
    = Deposit
    | Withdraw


type Route
    = Entry
    | Dashboard String
    | Money MoneyOperation String
    | Transfer String
    | BillPayment String
    | PaymentStatus String String


fromUrl : Url -> Maybe Route
fromUrl url =
    Parser.parse parser url


parser : Parser (Route -> a) a
parser =
    Parser.oneOf
        [ Parser.map Entry Parser.top
        , Parser.map Dashboard (s "accounts" </> segment)
        , Parser.map (Money Deposit) (s "accounts" </> segment </> s "deposit")
        , Parser.map (Money Withdraw) (s "accounts" </> segment </> s "withdraw")
        , Parser.map Transfer (s "accounts" </> segment </> s "transfer")
        , Parser.map BillPayment (s "accounts" </> segment </> s "bill-payment")
        , Parser.map PaymentStatus (s "accounts" </> segment </> s "bill-payments" </> segment)
        ]


segment : Parser (String -> a) a
segment =
    Parser.map (\raw -> Url.percentDecode raw |> Maybe.withDefault raw) string


toString : Route -> String
toString route =
    Url.Builder.absolute
        (case route of
            Entry ->
                []

            Dashboard accountId ->
                [ "accounts", accountId ]

            Money Deposit accountId ->
                [ "accounts", accountId, "deposit" ]

            Money Withdraw accountId ->
                [ "accounts", accountId, "withdraw" ]

            Transfer accountId ->
                [ "accounts", accountId, "transfer" ]

            BillPayment accountId ->
                [ "accounts", accountId, "bill-payment" ]

            PaymentStatus accountId paymentId ->
                [ "accounts", accountId, "bill-payments", paymentId ]
        )
        []


href : Route -> Attribute msg
href route =
    Html.Attributes.href (toString route)


push : Nav.Key -> Route -> Cmd msg
push key route =
    Nav.pushUrl key (toString route)
