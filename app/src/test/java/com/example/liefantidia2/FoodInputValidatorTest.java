package com.example.liefantidia2;

import org.junit.Test;
import static org.junit.Assert.*;

public class FoodInputValidatorTest {
    @Test public void rejectsNonFoodAndQuestions() {
        assertNotNull(FoodInputValidator.validate("タイヤ、輪ゴム、ギ酸メチル"));
        assertNotNull(FoodInputValidator.validate("東京タワーの高さは?"));
        assertNotNull(FoodInputValidator.validate("玉ねぎ、漂白剤"));
        assertNotNull(FoodInputValidator.validate("カレーの作り方を教えて"));
    }
    @Test public void permitsNormalIngredientLists() {
        assertNull(FoodInputValidator.validate("鶏むね肉、卵、玉ねぎ"));
        assertNull(FoodInputValidator.validate("しょうゆ\n味噌\nじゃがいも"));
    }
}
