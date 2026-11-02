module Format exposing
    ( dateTime
    , isValidAccountId
    , isValidAmount
    , thb
    , time
    )

{-| Formatting and validation matching the Angular UI's pipes and validators. -}

import Iso8601
import Regex exposing (Regex)
import Time


accountIdPattern : Regex
accountIdPattern =
    Regex.fromString "^[A-Za-z0-9_-]{1,64}$" |> Maybe.withDefault Regex.never


amountPattern : Regex
amountPattern =
    Regex.fromString "^(?:0*[1-9]\\d*(?:\\.\\d{1,2})?|0+\\.(?:0[1-9]|[1-9]\\d?))$"
        |> Maybe.withDefault Regex.never


isValidAccountId : String -> Bool
isValidAccountId =
    Regex.contains accountIdPattern


isValidAmount : String -> Bool
isValidAmount =
    Regex.contains amountPattern


{-| `thb 1234.5 == "฿1,234.50"` -}
thb : Float -> String
thb amount =
    let
        cents =
            round (abs amount * 100)

        sign =
            if amount < 0 && cents > 0 then
                "-"

            else
                ""
    in
    sign
        ++ "฿"
        ++ groupThousands (String.fromInt (cents // 100))
        ++ "."
        ++ String.padLeft 2 '0' (String.fromInt (modBy 100 cents))


groupThousands : String -> String
groupThousands digits =
    if String.length digits <= 3 then
        digits

    else
        groupThousands (String.dropRight 3 digits) ++ "," ++ String.right 3 digits


{-| Medium date and time, e.g. "Oct 22, 2026, 4:41:45 AM". -}
dateTime : Time.Zone -> String -> String
dateTime zone isoTimestamp =
    case Iso8601.toTime isoTimestamp of
        Ok posix ->
            monthName (Time.toMonth zone posix)
                ++ " "
                ++ String.fromInt (Time.toDay zone posix)
                ++ ", "
                ++ String.fromInt (Time.toYear zone posix)
                ++ ", "
                ++ clockTime zone posix

        Err _ ->
            isoTimestamp


{-| Medium time, e.g. "4:41:45 AM". -}
time : Time.Zone -> String -> String
time zone isoTimestamp =
    case Iso8601.toTime isoTimestamp of
        Ok posix ->
            clockTime zone posix

        Err _ ->
            isoTimestamp


clockTime : Time.Zone -> Time.Posix -> String
clockTime zone posix =
    let
        hour =
            Time.toHour zone posix

        twelveHour =
            if modBy 12 hour == 0 then
                12

            else
                modBy 12 hour

        period =
            if hour < 12 then
                "AM"

            else
                "PM"
    in
    String.fromInt twelveHour
        ++ ":"
        ++ twoDigits (Time.toMinute zone posix)
        ++ ":"
        ++ twoDigits (Time.toSecond zone posix)
        ++ " "
        ++ period


twoDigits : Int -> String
twoDigits =
    String.fromInt >> String.padLeft 2 '0'


monthName : Time.Month -> String
monthName month =
    case month of
        Time.Jan ->
            "Jan"

        Time.Feb ->
            "Feb"

        Time.Mar ->
            "Mar"

        Time.Apr ->
            "Apr"

        Time.May ->
            "May"

        Time.Jun ->
            "Jun"

        Time.Jul ->
            "Jul"

        Time.Aug ->
            "Aug"

        Time.Sep ->
            "Sep"

        Time.Oct ->
            "Oct"

        Time.Nov ->
            "Nov"

        Time.Dec ->
            "Dec"
