/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.superbiz.osgi.moviefun;

import javax.annotation.security.PermitAll;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.interceptor.Interceptors;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.List;

//START SNIPPET: code
@Stateless
public class Movies {

    @PersistenceContext(unitName = "movie-unit")
    private EntityManager entityManager;

    @Interceptors(AddInterceptor.class)
    public void addMovie(Movie movie) throws Exception {
        entityManager.persist(movie);
    }

    @Interceptors(DeleteInterceptor.class)
    public void deleteMovie(Movie movie) throws Exception {
        entityManager.remove(movie);
    }

    @PermitAll
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public List<Movie> getMovies() throws Exception {
        Query query = entityManager.createQuery("SELECT m from Movie as m");
        return query.getResultList();
    }
}
//END SNIPPET: code
