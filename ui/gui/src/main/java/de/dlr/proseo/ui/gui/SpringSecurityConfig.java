package de.dlr.proseo.ui.gui;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@Configuration
@EnableWebSecurity
public class SpringSecurityConfig extends WebSecurityConfigurerAdapter {
	 @Override
	    protected void configure(HttpSecurity http) throws Exception {
	        http
	            .authorizeRequests()
	            .antMatchers("/resources/**").permitAll()
	                .anyRequest().authenticated()
	                .and()
	            .formLogin()
	                .loginPage("/customlogin")
	                .permitAll()
	                .and()
	            .logout()
	            	.permitAll();
	    }
	

//	    @Bean
//	    public ViewResolver viewResolver() {
//	        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
//	        viewResolver.setPrefix("/BOOT-INF/classes/templates/");
//	        viewResolver.setSuffix(".ftl");
//	        return viewResolver;
//
//	    }
}