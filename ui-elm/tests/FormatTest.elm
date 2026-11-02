module FormatTest exposing (suite)

import Expect
import Format
import Route exposing (MoneyOperation(..), Route(..))
import Test exposing (Test, describe, test)
import Time
import Url


suite : Test
suite =
    describe "ui-elm"
        [ describe "Format.thb"
            [ test "uses the narrow baht symbol and two decimals" <|
                \_ -> Format.thb 100 |> Expect.equal "฿100.00"
            , test "groups thousands and pads cents" <|
                \_ -> Format.thb 1234567.5 |> Expect.equal "฿1,234,567.50"
            , test "rounds to the nearest satang" <|
                \_ -> Format.thb 0.005 |> Expect.equal "฿0.01"
            ]
        , describe "Format.isValidAmount"
            [ test "accepts positive amounts with up to two decimals" <|
                \_ ->
                    List.map Format.isValidAmount [ "1", "0.01", "10.5", "99.99" ]
                        |> Expect.equal [ True, True, True, True ]
            , test "rejects zero, negatives, extra decimals, and text" <|
                \_ ->
                    List.map Format.isValidAmount [ "", "0", "0.00", "-1", "1.234", "abc" ]
                        |> List.any identity
                        |> Expect.equal False
            ]
        , test "Format.isValidAccountId follows the API's account ID rule" <|
            \_ ->
                ( Format.isValidAccountId "account-123", Format.isValidAccountId "bad id" )
                    |> Expect.equal ( True, False )
        , test "Format.dateTime renders a medium date and time" <|
            \_ ->
                Format.dateTime Time.utc "2026-10-22T16:05:09Z"
                    |> Expect.equal "Oct 22, 2026, 4:05:09 PM"
        , describe "Route.fromUrl"
            [ test "parses the payment status route" <|
                \_ ->
                    routeOf "/accounts/account-123/bill-payments/abc"
                        |> Expect.equal (Just (PaymentStatus "account-123" "abc"))
            , test "parses money operations" <|
                \_ ->
                    routeOf "/accounts/account-123/withdraw"
                        |> Expect.equal (Just (Money Withdraw "account-123"))
            , test "rejects unknown paths" <|
                \_ -> routeOf "/nowhere" |> Expect.equal Nothing
            ]
        ]


routeOf : String -> Maybe Route
routeOf path =
    Url.fromString ("http://localhost" ++ path) |> Maybe.andThen Route.fromUrl
