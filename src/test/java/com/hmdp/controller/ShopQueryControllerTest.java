package com.hmdp.controller;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.*;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ShopQueryControllerTest {
    private IShopService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(IShopService.class); ShopController controller=new ShopController();
        ReflectionTestUtils.setField(controller,"shopService",service);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();
    }
    @Test void zeroCoordinatesAndPagingOptInArePassedWithDefaults() throws Exception {
        when(service.queryShopByType(1L,1,0D,0D,5000,true)).thenReturn(Result.ok(new ShopPage(List.of(),false)));
        mvc.perform(get("/shop/of/type?typeId=1&x=0&y=0&pageInfo=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isArray()).andExpect(jsonPath("$.data.hasMore").value(false));
        verify(service).queryShopByType(1L,1,0D,0D,5000,true);
    }
    @Test void legacyRequestsKeepArraysAndOrdinaryModePassesNullCoordinates() throws Exception {
        when(service.queryShopByType(1L,1,null,null,5000,false)).thenReturn(Result.ok(List.of()));
        mvc.perform(get("/shop/of/type?typeId=1")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(service).queryShopByType(1L,1,null,null,5000,false);
    }
    @Test void invalidNumericParametersAreClientErrorsRatherThanServerErrors() throws Exception {
        for (String params:List.of("typeId=abc","typeId=1&current=bad","typeId=1&x=abc&y=0",
                "typeId=9223372036854775808","typeId=1&radius=3.2","typeId=1&pageInfo=bad","current=1")) {
            mvc.perform(get("/shop/of/type?"+params)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(service);
    }
}
