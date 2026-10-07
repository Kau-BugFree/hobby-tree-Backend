package com.hobbytree.hobbytree.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GeneralExceptionAdviceTests {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        standaloneSetup(new TestController())
            .setControllerAdvice(new GeneralExceptionAdvice())
            .build();
  }

  @Test
  void malformedJsonReturns400() throws Exception {
    mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{broken"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
  }

  @Test
  void missingParameterReturns400() throws Exception {
    mvc.perform(get("/parameter"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
  }

  @Test
  void invalidParameterTypeReturns400() throws Exception {
    mvc.perform(get("/parameter").param("count", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
  }

  @Test
  void missingPathReturns404() throws Exception {
    mvc.perform(get("/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON404_1"));
  }

  @Test
  void wrongMethodReturns405WithAllowHeader() throws Exception {
    mvc.perform(post("/parameter"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "GET"))
        .andExpect(jsonPath("$.code").value("COMMON405_1"));
  }

  @Test
  void unsupportedContentTypeReturns415() throws Exception {
    mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("COMMON415_1"));
  }

  @Test
  void validationStillIncludesFieldErrors() throws Exception {
    mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.result.name").value("이름은 필수입니다."));
  }

  @Test
  void unexpectedExceptionStillReturns500() throws Exception {
    mvc.perform(get("/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("COMMON500_1"));
  }

  @Test
  void businessExceptionKeepsItsStatus() throws Exception {
    mvc.perform(get("/business"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMON404_1"));
  }

  @RestController
  static class TestController {
    @GetMapping("/parameter")
    int parameter(@RequestParam("count") int count) {
      return count;
    }

    @PostMapping(value = "/body", consumes = "application/json")
    Payload body(@Valid @RequestBody Payload payload) {
      return payload;
    }

    @GetMapping("/unexpected")
    void unexpected() {
      throw new IllegalStateException("internal detail");
    }

    @GetMapping("/business")
    void business() {
      throw new ProjectException(GeneralErrorCode.COMMON_NOT_FOUND);
    }
  }

  record Payload(@NotBlank(message = "이름은 필수입니다.") String name) {}
}
