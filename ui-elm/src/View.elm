module View exposing (amountInput, ariaHidden, ariaInvalid, ariaLabel, ariaLive, errorNotice, role)

import Api exposing (ApiError)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onBlur, onInput)


errorNotice : Maybe ApiError -> Html msg
errorNotice maybeError =
    case maybeError of
        Just error ->
            div [ class "notice notice-error compact", role "alert" ]
                [ div [] [ strong [] [ text error.code ], span [] [ text error.message ] ] ]

        Nothing ->
            text ""


amountInput :
    { value : String, showError : Bool, onChange : String -> msg, onTouch : msg }
    -> List (Html msg)
amountInput config =
    [ label [ for "amount" ] [ text "Amount" ]
    , div [ class "money-input" ]
        [ span [] [ text "฿" ]
        , input
            [ id "amount"
            , type_ "text"
            , attribute "inputmode" "decimal"
            , autocomplete False
            , placeholder "0.00"
            , value config.value
            , ariaInvalid config.showError
            , onInput config.onChange
            , onBlur config.onTouch
            ]
            []
        , small [] [ text "THB" ]
        ]
    , if config.showError then
        p [ class "field-error" ] [ text "Enter a positive amount with no more than two decimal places." ]

      else
        text ""
    ]


role : String -> Attribute msg
role =
    attribute "role"


ariaLabel : String -> Attribute msg
ariaLabel =
    attribute "aria-label"


ariaLive : String -> Attribute msg
ariaLive =
    attribute "aria-live"


ariaHidden : Attribute msg
ariaHidden =
    attribute "aria-hidden" "true"


ariaInvalid : Bool -> Attribute msg
ariaInvalid invalid =
    attribute "aria-invalid"
        (if invalid then
            "true"

         else
            "false"
        )
